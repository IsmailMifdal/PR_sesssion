package io.prsentinel.auth;

import java.time.Duration;

/**
 * Connexion GitHub. Identifiants OAuth de la GitHub App (onglet « General » de l'app : Client ID, Client
 * secret) ; l'URL de rappel déclarée dans l'app doit être {@code <publicUrl>/auth/github/callback}.
 *
 * @param publicUrl URL publique de l'API (pour l'URL de rappel)
 * @param frontUrl URL du front : seule destination des redirections (jamais tirée de la requête)
 */
public record AuthSettings(String clientId, String clientSecret, String publicUrl, String frontUrl,
        String oauthUrl, String apiUrl, Duration sessionTtl) {

    public AuthSettings {
        publicUrl = trimSlash(publicUrl);
        frontUrl = trimSlash(frontUrl);
        oauthUrl = trimSlash(oauthUrl);
        apiUrl = trimSlash(apiUrl);
    }

    public boolean enabled() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(publicUrl) && notBlank(frontUrl);
    }

    public String callbackUrl() {
        return publicUrl + "/auth/github/callback";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimSlash(String s) {
        return s == null ? null : s.strip().replaceAll("/+$", "");
    }
}
