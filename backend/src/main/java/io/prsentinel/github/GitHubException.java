package io.prsentinel.github;

import java.util.Set;

/** Erreur de l'API GitHub. Jamais de jeton dans le message. */
public class GitHubException extends RuntimeException {

    /** Erreurs qu'un nouvel essai ne corrigera pas (app désinstallée, dépôt supprimé, droits manquants). */
    public static final Set<Integer> PERMANENT_STATUSES = Set.of(403, 404, 410, 422);

    private final Integer status;

    public GitHubException(String message, Integer status) {
        super(message);
        this.status = status;
    }

    public Integer status() {
        return status;
    }

    public boolean isPermanent() {
        return status != null && PERMANENT_STATUSES.contains(status);
    }

    public boolean isUnauthorized() {
        return status != null && status == 401;
    }
}
