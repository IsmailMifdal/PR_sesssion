package io.prsentinel.review.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.prsentinel.auth.GitHubOAuthClient;
import io.prsentinel.auth.SessionStore;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.scan.Finding;
import io.prsentinel.support.PostgresTestBase;
import java.math.BigDecimal;
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

/** Retours 👍/👎 : enregistrement, modification, retrait, cloisonnement par dépôt, liste des contestés. */
@AutoConfigureMockMvc
class FeedbackTest extends PostgresTestBase {

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry r) {
        r.add("sentinel.auth.github-client-id", () -> "Iv1.client");
        r.add("sentinel.auth.github-client-secret", () -> "secret");
        r.add("sentinel.auth.public-url", () -> "https://api.example");
        r.add("sentinel.auth.front-url", () -> "https://front.example");
    }

    static final String OPERATOR = "Bearer dashboard-test-token-0123456789abcdef";

    @Autowired
    MockMvc mvc;
    @Autowired
    ReviewStore store;
    @Autowired
    SessionStore sessions;
    String bearer;
    long review;
    long mine;
    long hidden;

    @BeforeEach
    void seed() {
        review = store.startReview(42, "acme/widgets", 7, "a".repeat(40));
        var f = store.saveFindings(review, List.of(new Finding("semgrep", "sqli", "ERROR", "Repo.java", 3, "sqli")),
                List.of("h1"));
        store.saveVerdict(f.get(0).id(), "VRAI_PROBLEME", "CRITIQUE", "Injection.", null, 0.9, "gpt-4o");
        store.finish(review, "TERMINEE", 0, 0, BigDecimal.ZERO, true, null);
        mine = f.get(0).id();
        long other = store.startReview(7, "evil-corp/secret", 1, "b".repeat(40));
        hidden = store.saveFindings(other, List.of(new Finding("gitleaks", "key", "SECRET", "a.yml", 1, "k")),
                List.of("h2")).get(0).id();
        long uid = sessions.saveLogin(new SessionStore.GitHubUser(1, "octocat", null, null),
                new GitHubOAuthClient.Access(List.of(new GitHubOAuthClient.Installation(42, "acme")),
                        List.of("acme/widgets")));
        bearer = "Bearer " + sessions.createSession(uid, Duration.ofHours(1)).token();
    }

    @Test
    void thumbsDownWithCommentThenChangeThenWithdraw() throws Exception {
        mvc.perform(put("/api/findings/" + mine + "/feedback").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"utile\":false,\"commentaire\":\"  requête préparée en amont  \"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/reviews/" + review).header("Authorization", bearer))
                .andExpect(jsonPath("$.alertes[0].monAvis").value(false));
        mvc.perform(get("/api/feedback").header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].commentaire").value("requête préparée en amont"))
                .andExpect(jsonPath("$[0].auteur").value("octocat"))
                .andExpect(jsonPath("$[0].decision").value("VRAI_PROBLEME"))
                .andExpect(jsonPath("$[0].revueId").value(review));
        mvc.perform(get("/api/metrics").header("Authorization", bearer))
                .andExpect(jsonPath("$.avis_errones").value(1)).andExpect(jsonPath("$.avis_justes").value(0));

        // Changement d'avis : un seul retour par personne.
        mvc.perform(put("/api/findings/" + mine + "/feedback").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"utile\":true}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/metrics").header("Authorization", bearer))
                .andExpect(jsonPath("$.avis_errones").value(0)).andExpect(jsonPath("$.avis_justes").value(1));

        // L'opérateur donne son propre avis, distinct.
        mvc.perform(put("/api/findings/" + mine + "/feedback").header("Authorization", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"utile\":false}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/reviews/" + review).header("Authorization", bearer))
                .andExpect(jsonPath("$.alertes[0].monAvis").value(true));

        mvc.perform(delete("/api/findings/" + mine + "/feedback").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/reviews/" + review).header("Authorization", bearer))
                .andExpect(jsonPath("$.alertes[0].monAvis").doesNotExist());
    }

    @Test
    void cannotRateOrSeeFindingsOfOtherRepositories() throws Exception {
        mvc.perform(put("/api/findings/" + hidden + "/feedback").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"utile\":false}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/findings/" + hidden + "/feedback").header("Authorization", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"utile\":false,\"commentaire\":\"x\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/feedback").header("Authorization", bearer)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/feedback").header("Authorization", OPERATOR)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectsMissingVerdictAndTruncatesLongComments() throws Exception {
        mvc.perform(put("/api/findings/" + mine + "/feedback").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/findings/" + mine + "/feedback").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"utile\":false,\"commentaire\":\"" + "x".repeat(800) + "\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/feedback").header("Authorization", bearer))
                .andExpect(jsonPath("$[0].commentaire", org.hamcrest.Matchers.hasLength(500)));
    }
}
