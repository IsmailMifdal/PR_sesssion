package io.prsentinel.auth;

import io.prsentinel.auth.GitHubOAuthClient.OAuthException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Conditional;

/**
 * Connexion par GitHub.
 *
 * <ol>
 *   <li>{@code GET /auth/github/login} : état aléatoire posé en cookie (lié au navigateur, contre la connexion
 *       forcée), redirection vers GitHub ;</li>
 *   <li>{@code GET /auth/github/callback} : état vérifié, code échangé, profil et dépôts relevés, redirection
 *       vers le front avec un code de connexion à usage unique (2 min) dans le fragment d'URL ;</li>
 *   <li>{@code POST /auth/session} : le front échange ce code contre un jeton de session (Bearer).</li>
 * </ol>
 * Le front et l'API étant sur des domaines différents, la session ne repose sur aucun cookie tiers.
 */
@RestController
@Conditional(AuthConfig.Enabled.class)
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    static final String STATE_COOKIE = "sentinel_oauth_state";
    private static final String CALLBACK_PATH = "/auth/github";

    private final AuthSettings settings;
    private final GitHubOAuthClient github;
    private final SessionStore sessions;

    public AuthController(AuthSettings settings, GitHubOAuthClient github, SessionStore sessions) {
        this.settings = settings;
        this.github = github;
        this.sessions = sessions;
    }

    @GetMapping("/auth/github/login")
    public ResponseEntity<Void> login() {
        String state = Tokens.random("st_");
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state).httpOnly(true).secure(secureCookies())
                .sameSite("Lax").path(CALLBACK_PATH).maxAge(Duration.ofMinutes(10)).build();
        return ResponseEntity.status(302).header(HttpHeaders.SET_COOKIE, cookie.toString())
                .location(URI.create(github.authorizeUrl(state))).build();
    }

    @GetMapping("/auth/github/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            HttpServletRequest request) {
        ResponseCookie clear = ResponseCookie.from(STATE_COOKIE, "").httpOnly(true).secure(secureCookies())
                .sameSite("Lax").path(CALLBACK_PATH).maxAge(0).build();
        if (error != null) {
            return toFront("/connexion?erreur=refus", clear);
        }
        if (code == null || state == null || !sameState(state, cookie(request))) {
            return toFront("/connexion?erreur=etat", clear);
        }
        try {
            String token = github.exchangeCode(code);
            var user = github.user(token);
            List<String> repos = github.accessibleRepos(token);
            long userId = sessions.saveLogin(user, repos);
            log.info("connexion GitHub de {} ({} dépôt(s) visibles)", user.login(), repos.size());
            return toFront("/auth/callback#code=" + sessions.createLoginCode(userId), clear);
        } catch (OAuthException e) {
            log.warn("connexion GitHub refusée : {}", e.getMessage());
            return toFront("/connexion?erreur=github", clear);
        }
    }

    public record CodeRequest(String code) {}

    @PostMapping("/auth/session")
    public ResponseEntity<Map<String, Object>> session(@RequestBody(required = false) CodeRequest body) {
        var userId = sessions.redeemLoginCode(body == null ? null : body.code());
        if (userId.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        var s = sessions.createSession(userId.get(), settings.sessionTtl());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("token", s.token(), "expireLe", s.expiresAt().toString()));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            sessions.deleteSession(auth.substring(7));
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Void> toFront(String pathAndFragment, ResponseCookie clear) {
        return ResponseEntity.status(302).header(HttpHeaders.SET_COOKIE, clear.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer")
                .location(URI.create(settings.frontUrl() + pathAndFragment)).build();
    }

    private boolean secureCookies() {
        return settings.publicUrl().startsWith("https://");
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies()).filter(c -> STATE_COOKIE.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
    }

    private static boolean sameState(String given, String expected) {
        return expected != null && !expected.isEmpty() && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
