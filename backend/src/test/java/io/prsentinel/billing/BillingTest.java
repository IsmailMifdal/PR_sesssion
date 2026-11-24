package io.prsentinel.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.prsentinel.auth.GitHubOAuthClient;
import io.prsentinel.auth.SessionStore;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.support.PostgresTestBase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Facturation de bout en bout contre un faux Stripe : Checkout, portail, webhook signé, droits, quota. */
@AutoConfigureMockMvc
class BillingTest extends PostgresTestBase {

    static final String WHSEC = "whsec_test_secret";
    static final FakeStripe STRIPE = FakeStripe.start();

    @DynamicPropertySource
    static void billing(DynamicPropertyRegistry r) {
        r.add("sentinel.auth.github-client-id", () -> "Iv1.client");
        r.add("sentinel.auth.github-client-secret", () -> "secret");
        r.add("sentinel.auth.public-url", () -> "https://api.example");
        r.add("sentinel.auth.front-url", () -> "https://front.example");
        r.add("sentinel.billing.stripe-secret-key", () -> "sk_test_123");
        r.add("sentinel.billing.stripe-webhook-secret", () -> WHSEC);
        r.add("sentinel.billing.stripe-price-equipe", () -> "price_equipe");
        r.add("sentinel.billing.stripe-api-url", STRIPE::url);
        r.add("sentinel.billing.free-reviews-per-month", () -> "2");
    }

    @AfterAll
    static void stop() {
        STRIPE.server.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionStore sessions;
    @Autowired
    SubscriptionStore subscriptions;
    @Autowired
    ReviewStore reviews;
    String bearer;

    @BeforeEach
    void user() {
        STRIPE.requests.clear();
        STRIPE.subscriptions.clear();
        long uid = sessions.saveLogin(new SessionStore.GitHubUser(1, "octocat", null, null),
                new GitHubOAuthClient.Access(List.of(new GitHubOAuthClient.Installation(42, "acme")),
                        List.of("acme/widgets")));
        bearer = "Bearer " + sessions.createSession(uid, Duration.ofHours(1)).token();
    }

    static String sign(String payload, long t, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }

    void sendEvent(String type, String objectJson) throws Exception {
        String payload = "{\"id\":\"evt_1\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson + "}}";
        mvc.perform(post("/stripe/webhook").header("Stripe-Signature", sign(payload, Instant.now().getEpochSecond(), WHSEC))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
    }

    static String subscription(String id, String status, int seats) {
        return "{\"id\":\"" + id + "\",\"customer\":\"cus_1\",\"status\":\"" + status + "\",\"cancel_at_period_end\":false,"
                + "\"current_period_end\":1900000000,\"metadata\":{\"installation_id\":\"42\"},"
                + "\"items\":{\"data\":[{\"quantity\":" + seats + "}]}}";
    }

    @Test
    void signatureVerification() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        assertThat(StripeClient.verifySignature(sign("{\"a\":1}", now, WHSEC), body, WHSEC, Instant.now())).isTrue();
        assertThat(StripeClient.verifySignature(sign("{\"a\":1}", now, "whsec_other"), body, WHSEC, Instant.now())).isFalse();
        assertThat(StripeClient.verifySignature(sign("{\"a\":2}", now, WHSEC), body, WHSEC, Instant.now())).isFalse();
        assertThat(StripeClient.verifySignature(sign("{\"a\":1}", now - 600, WHSEC), body, WHSEC, Instant.now())).isFalse();
        assertThat(StripeClient.verifySignature("t=abc,v1=00", body, WHSEC, Instant.now())).isFalse();
        assertThat(StripeClient.verifySignature(null, body, WHSEC, Instant.now())).isFalse();
    }

    @Test
    void unsignedOrForgedWebhooksAreRejected() throws Exception {
        String payload = "{\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_1\"}}}";
        mvc.perform(post("/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/stripe/webhook").header("Stripe-Signature", sign(payload, Instant.now().getEpochSecond(), "whsec_x"))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        assertThat(subscriptions.find(42)).isEmpty();
    }

    @Test
    void checkoutThenWebhookActivatesTheTeamPlan() throws Exception {
        mvc.perform(get("/api/billing").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.actif").value(true))
                .andExpect(jsonPath("$.comptes[0].compte").value("acme"))
                .andExpect(jsonPath("$.comptes[0].offre").value("DECOUVERTE"))
                .andExpect(jsonPath("$.comptes[0].quota").value(2))
                .andExpect(jsonPath("$.comptes[0].essaiDisponible").value(true));

        mvc.perform(post("/api/billing/checkout").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installationId\":42,\"sieges\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value(STRIPE.url() + "/pay/cs_1"));
        String form = STRIPE.requests.stream().filter(r -> r.startsWith("POST /v1/checkout/sessions")).findFirst().orElseThrow();
        assertThat(form).contains("line_items%5B0%5D%5Bprice%5D=price_equipe", "line_items%5B0%5D%5Bquantity%5D=3",
                "subscription_data%5Bmetadata%5D%5Binstallation_id%5D=42", "subscription_data%5Btrial_period_days%5D=14",
                "success_url=https%3A%2F%2Ffront.example%2Fconsole%2Ffacturation%3Fstatut%3Dok", "auth=Bearer sk_test_123");

        // Le contenu de l'événement ne fait pas foi : l'état est relu chez Stripe.
        STRIPE.subscriptions.put("sub_1", subscription("sub_1", "trialing", 3));
        sendEvent("checkout.session.completed", "{\"id\":\"cs_1\",\"subscription\":\"sub_1\",\"customer\":\"cus_1\"}");
        mvc.perform(get("/api/billing").header("Authorization", bearer))
                .andExpect(jsonPath("$.comptes[0].offre").value("EQUIPE"))
                .andExpect(jsonPath("$.comptes[0].statut").value("trialing"))
                .andExpect(jsonPath("$.comptes[0].sieges").value(3))
                .andExpect(jsonPath("$.comptes[0].quota").doesNotExist())
                .andExpect(jsonPath("$.comptes[0].gerable").value(true));

        // Déjà abonné : pas de second Checkout ; le portail gère le reste.
        mvc.perform(post("/api/billing/checkout").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"installationId\":42,\"sieges\":1}")).andExpect(status().isConflict());
        mvc.perform(post("/api/billing/portal").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"installationId\":42}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(STRIPE.url() + "/portal/bps_1"));

        // Résiliation : retour à l'offre gratuite.
        STRIPE.subscriptions.put("sub_1", subscription("sub_1", "canceled", 3));
        sendEvent("customer.subscription.deleted", "{\"id\":\"sub_1\"}");
        assertThat(subscriptions.find(42).orElseThrow().offre()).isEqualTo(Plans.DECOUVERTE);
    }

    @Test
    void lateEventForAnOldSubscriptionDoesNotOverrideTheActiveOne() throws Exception {
        STRIPE.subscriptions.put("sub_new", subscription("sub_new", "active", 5));
        sendEvent("customer.subscription.created", "{\"id\":\"sub_new\"}");
        STRIPE.subscriptions.put("sub_old", subscription("sub_old", "canceled", 1));
        sendEvent("customer.subscription.deleted", "{\"id\":\"sub_old\"}");
        var sub = subscriptions.find(42).orElseThrow();
        assertThat(sub.subscriptionId()).isEqualTo("sub_new");
        assertThat(sub.offre()).isEqualTo(Plans.EQUIPE);
    }

    @Test
    void usersCannotBuyOrManageOtherAccounts() throws Exception {
        mvc.perform(post("/api/billing/checkout").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"installationId\":999,\"sieges\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/billing/portal").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"installationId\":999}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"installationId\":42}")).andExpect(status().isUnauthorized());
        assertThat(STRIPE.requests).isEmpty();
    }

    @Test
    void monthlyQuotaOfTheFreePlan() throws Exception {
        QuotaPolicy policy = QuotaPolicy.monthly(subscriptions, 2);
        assertThat(policy.check(42).allowed()).isTrue();
        for (int i = 0; i < 2; i++) {
            long id = reviews.startReview(42, "acme/widgets", i + 1, "a".repeat(40));
            reviews.finish(id, "TERMINEE", 0, 0, BigDecimal.ZERO, false, null);
        }
        long skipped = reviews.startReview(42, "acme/widgets", 9, "b".repeat(40));
        reviews.finish(skipped, "IGNOREE", 0, 0, BigDecimal.ZERO, false, null); // les refus ne comptent pas
        assertThat(policy.check(42)).isEqualTo(new QuotaPolicy.Quota(false, 2, 2));
        assertThat(policy.check(7).allowed()).isTrue(); // autre installation

        STRIPE.subscriptions.put("sub_1", subscription("sub_1", "past_due", 1)); // relance de paiement : accès maintenu
        sendEvent("customer.subscription.updated", "{\"id\":\"sub_1\"}");
        assertThat(policy.check(42).allowed()).isTrue();
    }

    /** Faux Stripe : sessions Checkout et portail, lecture d'abonnement. */
    static final class FakeStripe {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Map<String, String> subscriptions = new ConcurrentHashMap<>();

        private FakeStripe(HttpServer server) {
            this.server = server;
        }

        static FakeStripe start() {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeStripe f = new FakeStripe(s);
                s.createContext("/", f::handle);
                s.start();
                return f;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestMethod() + " " + path + " " + body + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            String out;
            int code = 200;
            if (path.equals("/v1/checkout/sessions")) {
                out = "{\"id\":\"cs_1\",\"url\":\"" + url() + "/pay/cs_1\"}";
            } else if (path.equals("/v1/billing_portal/sessions")) {
                out = "{\"id\":\"bps_1\",\"url\":\"" + url() + "/portal/bps_1\"}";
            } else if (path.startsWith("/v1/subscriptions/") && subscriptions.containsKey(path.substring(18))) {
                out = subscriptions.get(path.substring(18));
            } else {
                code = 404;
                out = "{\"error\":{\"message\":\"No such resource\"}}";
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }
}
