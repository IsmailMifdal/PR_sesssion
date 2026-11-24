package io.prsentinel.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.prsentinel.auth.GitHubOAuthClient;
import io.prsentinel.auth.SessionStore;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.scan.Finding;
import io.prsentinel.support.PostgresTestBase;
import io.prsentinel.webhook.WebhookSignature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** RGPD : conservation, purge à la désinstallation, export et effacement du compte. */
@AutoConfigureMockMvc
class PrivacyTest extends PostgresTestBase {

    static final byte[] SECRET = "test-webhook-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry r) {
        r.add("sentinel.auth.github-client-id", () -> "Iv1.client");
        r.add("sentinel.auth.github-client-secret", () -> "secret");
        r.add("sentinel.auth.public-url", () -> "https://api.example");
        r.add("sentinel.auth.front-url", () -> "https://front.example");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ReviewStore store;
    @Autowired
    SessionStore sessions;
    @Autowired
    DataPurge purge;
    long uid;
    String bearer;

    @BeforeEach
    void seed() {
        uid = sessions.saveLogin(new SessionStore.GitHubUser(1, "octocat", "Mona", null),
                new GitHubOAuthClient.Access(List.of(new GitHubOAuthClient.Installation(42, "acme")),
                        List.of("acme/widgets")));
        bearer = "Bearer " + sessions.createSession(uid, Duration.ofHours(1)).token();
    }

    long review(long installation, String repo, int pr) {
        long id = store.startReview(installation, repo, pr, "a".repeat(40));
        var f = store.saveFindings(id, List.of(new Finding("semgrep", "r", "ERROR", "A.java", 1, "m")), List.of("h"));
        store.saveVerdict(f.get(0).id(), "VRAI_PROBLEME", "HAUTE", "x", null, 0.9, "gpt-4o");
        store.finish(id, "TERMINEE", 0, 0, BigDecimal.ZERO, false, null);
        return id;
    }

    int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    void webhook(String event, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/webhooks/github").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-GitHub-Event", event).header("X-GitHub-Delivery", "d-" + System.nanoTime())
                        .header("X-Hub-Signature-256", WebhookSignature.sign(SECRET, body)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("purged"));
    }

    @Test
    void retentionDeletesOldReviewsWithTheirFindingsAndFeedback() throws Exception {
        long old = review(42, "acme/widgets", 1);
        long recent = review(42, "acme/widgets", 2);
        jdbc.sql("UPDATE review SET debut = now() - interval '400 days' WHERE id = ?").param(old).update();
        long oldFinding = jdbc.sql("SELECT id FROM finding WHERE review_id = ?").param(old).query(Long.class).single();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/findings/" + oldFinding + "/feedback")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"utile\":false}"))
                .andExpect(status().isNoContent());

        var purged = purge.olderThan(365);
        assertThat(purged.reviews()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM review").query(Long.class).list()).containsExactly(recent);
        assertThat(count("finding")).isEqualTo(1);
        assertThat(count("verdict")).isEqualTo(1);
        assertThat(count("verdict_feedback")).isZero();
    }

    @Test
    void uninstallingTheAppErasesThatAccountOnly() throws Exception {
        review(42, "acme/widgets", 1);
        review(7, "other/repo", 1);
        webhook("installation", "{\"action\":\"deleted\",\"installation\":{\"id\":42}}");
        assertThat(jdbc.sql("SELECT nom_complet FROM repository").query(String.class).list()).containsExactly("other/repo");
        assertThat(count("review")).isEqualTo(1);
        assertThat(count("user_installation")).isZero();
    }

    @Test
    void removedRepositoriesAreErased() throws Exception {
        review(42, "acme/widgets", 1);
        review(42, "acme/keep", 1);
        webhook("installation_repositories", "{\"action\":\"removed\",\"installation\":{\"id\":42},"
                + "\"repositories_removed\":[{\"full_name\":\"acme/widgets\"},{\"full_name\":\"bad name\"}]}");
        assertThat(jdbc.sql("SELECT nom_complet FROM repository").query(String.class).list()).containsExactly("acme/keep");
        assertThat(count("user_repo")).isZero();
    }

    @Test
    void exportThenDeleteAccount() throws Exception {
        mvc.perform(get("/api/me/export").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.profil.login").value("octocat"))
                .andExpect(jsonPath("$.depots[0]").value("acme/widgets"))
                .andExpect(jsonPath("$.installations[0].compte").value("acme"))
                .andExpect(jsonPath("$.conservationRevuesJours").value(365));

        mvc.perform(delete("/api/me").header("Authorization", bearer)).andExpect(status().isNoContent());
        assertThat(count("app_user")).isZero();
        assertThat(count("user_session")).isZero();
        mvc.perform(get("/api/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    @Test
    void operatorHasNoPersonalAccount() throws Exception {
        String op = "Bearer dashboard-test-token-0123456789abcdef";
        mvc.perform(delete("/api/me").header("Authorization", op)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/export").header("Authorization", op)).andExpect(status().isBadRequest());
    }
}
