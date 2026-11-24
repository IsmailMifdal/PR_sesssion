package io.prsentinel.billing;

/** Décide si une nouvelle revue est permise pour une installation (offre Découverte : quota mensuel). */
public interface QuotaPolicy {

    record Quota(boolean allowed, int used, int limit) {}

    /** Instance auto-hébergée ou quotas désactivés : aucune limite. */
    QuotaPolicy UNLIMITED = id -> new Quota(true, 0, -1);

    Quota check(long githubInstallationId);

    /** Offre payante (abonnement actif, en essai ou en relance de paiement) : illimité ; sinon quota mensuel. */
    static QuotaPolicy monthly(SubscriptionStore store, int freeReviewsPerMonth) {
        return id -> {
            if (store.find(id).map(s -> Plans.isPaid(s.statut())).orElse(false)) {
                return new Quota(true, 0, -1);
            }
            int used = store.reviewsThisMonth(id);
            return new Quota(used < freeReviewsPerMonth, used, freeReviewsPerMonth);
        };
    }
}
