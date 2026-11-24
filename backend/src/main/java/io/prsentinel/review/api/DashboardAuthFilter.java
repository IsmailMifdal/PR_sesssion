package io.prsentinel.review.api;

import io.prsentinel.auth.SessionStore;
import io.prsentinel.auth.Viewer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protège {@code /api/**} : les revues contiennent des failles de sécurité. Deux jetons Bearer acceptés :
 * <ul>
 *   <li>le jeton d'opérateur ({@code SENTINEL_DASHBOARD_TOKEN}, 32 caractères minimum), comparé à temps
 *       constant : accès à tout ;</li>
 *   <li>un jeton de session obtenu par la connexion GitHub : accès aux seuls dépôts de l'utilisateur.</li>
 * </ul>
 * Sans aucun des deux modes configurés, l'API du tableau de bord est désactivée (503).
 */
public class DashboardAuthFilter extends OncePerRequestFilter {

    public static final int MIN_TOKEN_CHARS = 32;
    private final byte[] token;
    private final SessionStore sessions;

    public DashboardAuthFilter(String token) {
        this(token, null);
    }

    /** @param sessions {@code null} si la connexion GitHub n'est pas configurée */
    public DashboardAuthFilter(String token, SessionStore sessions) {
        this.token = token != null && token.length() >= MIN_TOKEN_CHARS ? token.getBytes(StandardCharsets.UTF_8) : null;
        this.sessions = sessions;
    }

    /**
     * Décision sur le chemin normalisé par le conteneur ({@code servletPath} : paramètres {@code ;x} retirés,
     * {@code //} et {@code ..} résolus), jamais sur l'URI brute : {@code /api;x=1/reviews} est bien protégé.
     */
    static boolean isDashboardPath(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("/api") || lower.startsWith("/api/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isDashboardPath(request) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token == null && sessions == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "tableau de bord désactivé");
            return;
        }
        String header = request.getHeader("Authorization");
        String given = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        Optional<Viewer> viewer = authenticate(given);
        if (viewer.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(Viewer.ATTR, viewer.get());
        chain.doFilter(request, response);
    }

    private Optional<Viewer> authenticate(String given) {
        if (given.isEmpty()) {
            return Optional.empty();
        }
        if (token != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            return Optional.of(Viewer.operator());
        }
        return sessions == null ? Optional.empty() : sessions.findSession(given);
    }
}
