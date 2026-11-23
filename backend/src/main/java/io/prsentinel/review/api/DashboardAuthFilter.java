package io.prsentinel.review.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protège {@code /api/**} : les revues contiennent des failles de sécurité. Jeton Bearer comparé à temps
 * constant ; sans jeton configuré (32 caractères minimum), l'API du tableau de bord est désactivée.
 */
public class DashboardAuthFilter extends OncePerRequestFilter {

    public static final int MIN_TOKEN_CHARS = 32;
    private final byte[] token;

    public DashboardAuthFilter(String token) {
        this.token = token != null && token.length() >= MIN_TOKEN_CHARS ? token.getBytes(StandardCharsets.UTF_8) : null;
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
        if (token == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "tableau de bord désactivé");
            return;
        }
        String header = request.getHeader("Authorization");
        byte[] given = header != null && header.startsWith("Bearer ")
                ? header.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(token, given)) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
