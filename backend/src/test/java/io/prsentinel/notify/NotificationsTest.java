package io.prsentinel.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.prsentinel.auth.GitHubOAuthClient;
import io.prsentinel.auth.SessionStore;
import io.prsentinel.privacy.DataPurge;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.scan.Finding;
import io.prsentinel.support.PostgresTestBase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Notifications : chiffrement, validation Slack, alertes après revue, rapport hebdomadaire, droits. */
@AutoConfigureMockMvc
class NotificationsTest extends PostgresTestBase {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final List<String> SLACK = new CopyOnWriteArrayList<>();
    static final HttpServer SERVER = startSlack();

    static HttpServer startSlack() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/services/", ex -> {
                SLACK.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String prefix() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/services/";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sentinel.auth.github-client-id", () -> "Iv1.client");
        r.add("sentinel.auth.github-client-secret", () -> "secret");
        r.add("sentinel.auth.public-url", () -> "https://api.example");
        r.add("sentinel.auth.front-url", () -> "https://front.example");
        r.add("sentinel.notify.encryption-key", () -> KEY);
        r.add("sentinel.notify.slack-prefix", NotificationsTest::prefix);
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    record Sent(List<String> to, String subject, String text) {}

    static final List<Sent> MAILS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingMail {
        @Bean
        @Primary
        Mailer recordingMailer() {
            return new Mailer() {
                public boolean enabled() {
                    return true;
                }

                public void send(List<String> to, String subject, String text) {
                    MAILS.add(new Sent(to, subject, text));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ReviewStore reviews;
    @Autowired
    SessionStore sessions;
    @Autowired
    NotificationStore store;
    @Autowired
    ReviewNotifier notifier;
    @Autowired
    DataPurge purge;
    String bearer;

    @BeforeEach
    void seed() {
        SLACK.clear();
        MAILS.clear();
        long uid = sessions.saveLogin(new SessionStore.GitHubUser(1, "octocat", null, null),
                new GitHubOAuthClient.Access(List.of(new GitHubOAuthClient.Installation(42, "acme")),
                        List.of("acme/widgets")));
        bearer = "Bearer " + sessions.createSession(uid, Duration.ofHours(1)).token();
    }

    long review(String gravite, boolean blocking) {
        long id = reviews.startReview(42, "acme/widgets", 7, "a".repeat(40));
        var f = reviews.saveFindings(id, List.of(new Finding("semgrep", "spring-sqli", "ERROR", "src/Repo.java", 17, "m"),
                new Finding("semgrep", "md5", "WARNING", "src/Cache.java", 3, "m")), List.of("h1", "h2"));
        reviews.saveVerdict(f.get(0).id(), "VRAI_PROBLEME", gravite, "x", null, 0.9, "gpt-4o");
        reviews.saveVerdict(f.get(1).id(), "FAUX_POSITIF", "BASSE", "x", null, 0.9, "gpt-4o-mini");
        reviews.finish(id, "TERMINEE", 0, 0, new BigDecimal("0.0070"), blocking, null);
        return id;
    }

    void configure(String json) throws Exception {
        mvc.perform(put("/api/notifications/42").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isNoContent());
    }

    @Test
    void secretBox() {
        SecretBox box = SecretBox.fromBase64(KEY);
        String sealed = box.encrypt("https://hooks.slack.com/services/T1/B2/abc");
        assertThat(sealed).doesNotContain("hooks.slack.com");
        assertThat(box.decrypt(sealed)).isEqualTo("https://hooks.slack.com/services/T1/B2/abc");
        assertThat(box.encrypt("x")).isNotEqualTo(box.encrypt("x")); // nonce aléatoire
        byte[] raw = Base64.getDecoder().decode(sealed);
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> box.decrypt(Base64.getEncoder().encodeToString(raw))).isInstanceOf(IllegalStateException.class);
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> SecretBox.fromBase64(Base64.getEncoder().encodeToString(other)).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
        assertThat(SecretBox.fromBase64("trop-court")).isNull();
        assertThat(SecretBox.fromBase64("")).isNull();
    }

    @Test
    void onlySlackWebhookUrlsAreAccepted() {
        SlackClient prod = new SlackClient("https://hooks.slack.com/services/", HttpClient.newHttpClient());
        assertThat(prod.isValidWebhook("https://hooks.slack.com/services/T00/B00/XXXXabcd")).isTrue();
        assertThat(prod.isValidWebhook("http://hooks.slack.com/services/T00/B00/XXXX")).isFalse();
        assertThat(prod.isValidWebhook("https://hooks.slack.com.evil.example/services/T00/B00/XXXX")).isFalse();
        assertThat(prod.isValidWebhook("https://hooks.slack.com/services/T00/B00/../../x")).isFalse();
        assertThat(prod.isValidWebhook("https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(SlackClient.escape("<!channel> & <https://x|y>")).isEqualTo("&lt;!channel&gt; &amp; &lt;https://x|y&gt;");
    }

    @Test
    void settingsAreScopedAndTheWebhookIsWriteOnly() throws Exception {
        String url = prefix() + "T1/B2/secretToken9f";
        configure("{\"slackWebhook\":\"" + url + "\",\"slackNiveau\":\"CRITIQUE\",\"emails\":[\"dev@acme.example\"],\"rapportHebdo\":true}");
        String body = mvc.perform(get("/api/notifications").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.slackActif").value(true))
                .andExpect(jsonPath("$.comptes[0].slackConfigure").value(true))
                .andExpect(jsonPath("$.comptes[0].slackIndice").value("en9f"))
                .andExpect(jsonPath("$.comptes[0].emails[0]").value("dev@acme.example"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secretToken");
        assertThat(jdbc.sql("SELECT slack_webhook_chiffre FROM notification_settings").query(String.class).single())
                .doesNotContain("secretToken");

        mvc.perform(put("/api/notifications/42").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slackWebhook\":\"https://evil.example/hook\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/notifications/42").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"emails\":[\"pas-une-adresse\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/notifications/999").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rapportHebdo\":false}")).andExpect(status().isForbidden());

        // Test : envoyé une fois, puis limité.
        mvc.perform(post("/api/notifications/42/test-slack").header("Authorization", bearer)).andExpect(status().isNoContent());
        assertThat(SLACK).singleElement().asString().contains("Message de test").contains("acme");
        mvc.perform(post("/api/notifications/42/test-slack").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests());

        // Suppression du webhook (chaîne vide), le reste inchangé.
        configure("{\"slackWebhook\":\"\"}");
        assertThat(store.get(42).slackSealed()).isNull();
        assertThat(store.get(42).emails()).containsExactly("dev@acme.example");
    }

    @Test
    void reviewAlertsFollowTheChosenLevel() throws Exception {
        configure("{\"slackWebhook\":\"" + prefix() + "T1/B2/tok\",\"slackNiveau\":\"CRITIQUE\"}");
        notifier.published(review("HAUTE", false));
        assertThat(SLACK).isEmpty(); // pas de faille critique

        notifier.published(review("CRITIQUE", true));
        assertThat(SLACK).singleElement().asString().contains("Faille critique").contains("acme/widgets#7")
                .contains("1 critique").contains("spring-sqli").contains("src/Repo.java:17")
                .contains("https://front.example/console/revues/");

        SLACK.clear();
        configure("{\"slackNiveau\":\"VRAI_PROBLEME\"}");
        notifier.published(review("MOYENNE", false));
        assertThat(SLACK).singleElement().asString().contains("Problème de sécurité").contains("1 moyenne");
    }

    @Test
    void weeklyReportIsSentOncePerWeekAndAccount() throws Exception {
        configure("{\"emails\":[\"a@acme.example\",\"b@acme.example\"],\"rapportHebdo\":true}");
        review("CRITIQUE", true);
        // Lundi suivant la revue : la semaine écoulée contient la revue.
        var now = Instant.now();
        var nextMonday = now.atOffset(ZoneOffset.UTC).toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        var job = new WeeklyReport(store, MAILS_MAILER, "https://front.example",
                Clock.fixed(nextMonday.atTime(7, 13).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        job.run();
        job.run(); // seconde exécution (autre instance) : rien
        assertThat(MAILS).singleElement().satisfies(m -> {
            assertThat(m.to()).containsExactly("a@acme.example", "b@acme.example");
            assertThat(m.subject()).contains("1 faille").contains("1 fausse alerte écartée");
            assertThat(m.text()).contains("Pull requests analysées : 1").contains("Revues bloquantes (faille critique) : 1")
                    .contains("acme/widgets (1)").contains("https://front.example/console/notifications");
        });
    }

    @Test
    void testEmailAndPurgeOnUninstall() throws Exception {
        configure("{\"emails\":[\"a@acme.example\"]}");
        mvc.perform(post("/api/notifications/42/test-email").header("Authorization", bearer)).andExpect(status().isNoContent());
        assertThat(MAILS).hasSize(1);
        mvc.perform(post("/api/notifications/42/test-email").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests());
        purge.installation(42);
        assertThat(jdbc.sql("SELECT count(*) FROM notification_settings").query(Integer.class).single()).isZero();
    }

    static final Mailer MAILS_MAILER = new Mailer() {
        public boolean enabled() {
            return true;
        }

        public void send(List<String> to, String subject, String text) {
            MAILS.add(new Sent(to, subject, text));
        }
    };
}
