package io.prsentinel.auth;

import java.util.List;

/**
 * Qui consulte le tableau de bord : l'opérateur (jeton d'administration, voit tout) ou un utilisateur connecté
 * par GitHub (ne voit que ses dépôts). Posé en attribut de requête par le filtre d'authentification.
 */
public record Viewer(boolean admin, long userId, String login, String nom, String avatarUrl, List<String> depots) {

    public static final String ATTR = "sentinel.viewer";

    public static Viewer operator() {
        return new Viewer(true, -1, "opérateur", null, null, List.of());
    }
}
