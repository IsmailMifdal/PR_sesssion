package io.prsentinel.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.support.PostgresTestBase;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Parcours complet de la connexion GitHub, contre un faux GitHub (OAuth + API). */
@AutoConfigureMockMvc
class AuthFlowTest extends PostgresTestBase {

    static final String FRONT = "https://pr-sentinel.example";
    static final FakeOAuth GITHUB = FakeOAuth.start();

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry r) {
        r.add("sentinel.auth.github-client-id", () -> "Iv1.client");
        r.add("sentinel.auth.github-client-secret", () -> "client-secret-value");
        r.add("sentinel.auth.public-url", () -> "https://api.pr-sentinel.example");
        r.add("sentinel.auth.front-url", () -> FRONT + "/");
        r.add("sentinel.auth.oauth-url", GITHUB::url);
        r.add("sentinel.github.api-url", GITHUB::url);
        r.add("sentinel.github.app-slug", () -> "pr-sentinel-test");
    }

    @AfterAll
    static void stop() {
        GITHUB.server.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ReviewStore store;
    final ObjectMapper json = new ObjectMapper();
    long mine;
    long other;

    @BeforeEach
    void seed() {
        GITHUB.requests.clear();
        GITHUB.tokenError = null;
        mine = store.startReview(1, "acme/widgets", 7, "a".repeat(40));
        store.finish(mine, "TERMINEE", 10, 1, new BigDecimal("0.001"), true, null);
        other = store.startReview(2, "evil-corp/secret", 3, "b".repeat(40));
        store.finish(other, "TERMINEE", 10, 1, new BigDecimal("0.002"), false, null);
    }

    /** Étapes 1 et 2 : renvoie l'adresse de redirection vers le front. */
    String loginThroughGitHub() throws Exception {
        MockHttpServletResponse start = mvc.perform(get("/auth/github/login")).andExpect(status().isFound())
                .andReturn().getResponse();
        URI authorize = URI.create(start.getRedirectedUrl());
        assertThat(authorize.toString()).startsWith(GITHUB.url() + "/login/oauth/authorize?")
                .contains("client_id=Iv1.client")
                .contains("redirect_uri=https%3A%2F%2Fapi.pr-sentinel.example%2Fauth%2Fgithub%2Fcallback");
        Cookie cookie = start.getCookie(AuthController.STATE_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        String state = authorize.getQuery().replaceAll(".*state=([^&]+).*", "$1");
        return mvc.perform(get("/auth/github/callback").param("code", "gh-code").param("state", state).cookie(cookie))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
    }

    String redeem(String code) throws Exception {
        String body = mvc.perform(post("/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code)))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("token").asText();
    }

    @Test
    void fullLoginScopesTheDashboardToTheUsersRepositories() throws Exception {
        String front = loginThroughGitHub();
        assertThat(front).startsWith(FRONT + "/auth/callback#code=plc_");
        String token = redeem(front.substring(front.indexOf("code=") + 5));
        assertThat(token).startsWith("pss_");

        // Le secret client n'est envoyé qu'à l'échange du code ; le jeton utilisateur, qu'à l'API.
        assertThat(GITHUB.requests).anySatisfy(r -> assertThat(r).contains("client_secret=client-secret-value"));
        assertThat(GITHUB.requests).anySatisfy(r -> assertThat(r).startsWith("GET /user/installations/42/repositories"));

        String bearer = "Bearer " + token;
        mvc.perform(get("/api/me").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("octocat"))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.avatarUrl").value("https://avatars.githubusercontent.com/u/1"))
                .andExpect(jsonPath("$.depots[0]").value("acme/widgets"));
        mvc.perform(get("/api/reviews").header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].depot").value("acme/widgets"));
        mvc.perform(get("/api/reviews/" + other).header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/" + mine).header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/metrics").header("Authorization", bearer)).andExpect(jsonPath("$.revues").value(1));
        mvc.perform(get("/api/onboarding").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.installUrl").value("https://github.test/apps/pr-sentinel-test/installations/new"))
                .andExpect(jsonPath("$.installations").value(2))
                .andExpect(jsonPath("$.depots").value(1))
                .andExpect(jsonPath("$.revues").value(1))
                .andExpect(jsonPath("$.premiereRevueId").value(mine));

        // Déconnexion : le jeton ne vaut plus rien.
        mvc.perform(post("/auth/logout").header("Authorization", bearer)).andExpect(status().isNoContent());
        mvc.perform(get("/api/reviews").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginCodeIsSingleUse() throws Exception {
        String front = loginThroughGitHub();
        String code = front.substring(front.indexOf("code=") + 5);
        redeem(code);
        mvc.perform(post("/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/session").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"plc_forged\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callbackWithoutMatchingStateCookieIsRejected() throws Exception {
        String to = mvc.perform(get("/auth/github/callback").param("code", "x").param("state", "st_attacker")
                .cookie(new Cookie(AuthController.STATE_COOKIE, "st_victim"))).andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(to).isEqualTo(FRONT + "/connexion?erreur=etat");
        mvc.perform(get("/auth/github/callback").param("code", "x").param("state", "st_x"))
                .andExpect(header().string("Location", FRONT + "/connexion?erreur=etat"));
        assertThat(GITHUB.requests).isEmpty(); // aucun appel à GitHub
    }

    @Test
    void gitHubRefusalAndUserDenialGoBackToLogin() throws Exception {
        GITHUB.tokenError = "bad_verification_code";
        assertThat(loginThroughGitHub()).isEqualTo(FRONT + "/connexion?erreur=github");
        mvc.perform(get("/auth/github/callback").param("error", "access_denied"))
                .andExpect(header().string("Location", FRONT + "/connexion?erreur=refus"));
    }

    @Test
    void sessionTokensAndOperatorTokenCoexist() throws Exception {
        mvc.perform(get("/api/reviews").header("Authorization", "Bearer dashboard-test-token-0123456789abcdef"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/me").header("Authorization", "Bearer dashboard-test-token-0123456789abcdef"))
                .andExpect(jsonPath("$.admin").value(true));
        mvc.perform(get("/api/reviews").header("Authorization", "Bearer pss_forged")).andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsTheFrontToRedeemItsCode() throws Exception {
        mvc.perform(options("/auth/session").header("Origin", FRONT).header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", FRONT));
        mvc.perform(options("/auth/session").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }

    @Test
    void avatarsOutsideGitHubAreDropped() {
        assertThat(SessionStore.safeAvatar("https://evil.example/x.png")).isNull();
        assertThat(SessionStore.safeAvatar("https://avatars.githubusercontent.com/u/1")).isNotNull();
    }

    /** Faux GitHub : OAuth, profil, installations (dont une pagination) et dépôts. */
    static final class FakeOAuth {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile String tokenError;

        private FakeOAuth(HttpServer server) {
            this.server = server;
        }

        static FakeOAuth start() {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeOAuth f = new FakeOAuth(s);
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
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            requests.add(ex.getRequestMethod() + " " + path + (ex.getRequestURI().getQuery() == null ? "" : "?"
                    + ex.getRequestURI().getQuery()) + " " + body);
            String out;
            if (path.equals("/login/oauth/access_token")) {
                out = tokenError != null ? "{\"error\":\"" + tokenError + "\"}" : "{\"access_token\":\"ghu_user\"}";
            } else if (!"Bearer ghu_user".equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            } else if (path.equals("/user")) {
                out = "{\"id\":1,\"login\":\"octocat\",\"name\":\"Mona\","
                        + "\"avatar_url\":\"https://avatars.githubusercontent.com/u/1\"}";
            } else if (path.equals("/user/installations") && ex.getRequestURI().getQuery().contains("page=2")) {
                out = "{\"installations\":[{\"id\":43,\"account\":{\"login\":\"acme-labs\"}}]}";
            } else if (path.equals("/user/installations")) {
                ex.getResponseHeaders().add("Link", "<" + url() + "/user/installations?per_page=100&page=2>; rel=\"next\"");
                out = "{\"installations\":[{\"id\":42,\"account\":{\"login\":\"acme\"}}]}";
            } else if (path.equals("/user/installations/42/repositories")) {
                out = "{\"repositories\":[{\"full_name\":\"acme/widgets\"},{\"full_name\":\"bad name\"}]}";
            } else if (path.equals("/user/installations/43/repositories")) {
                out = "{\"repositories\":[]}";
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }
}
