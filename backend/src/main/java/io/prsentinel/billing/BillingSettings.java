package io.prsentinel.billing;

/**
 * Facturation Stripe.
 *
 * @param secretKey clé secrète Stripe (rôle API seulement)
 * @param webhookSecret secret de signature du point de terminaison de webhook ({@code whsec_...})
 * @param priceEquipe identifiant du prix récurrent de l'offre Équipe, facturé au siège ({@code price_...})
 * @param frontUrl URL du front (retours de Checkout et du portail client)
 * @param quotasEnabled applique le quota de l'offre Découverte (rôle worker) ; indépendant des clés Stripe
 */
public record BillingSettings(String secretKey, String webhookSecret, String priceEquipe, String stripeApi,
        String frontUrl, int freeReviewsPerMonth, int trialDays, boolean quotasEnabled) {

    public BillingSettings {
        stripeApi = stripeApi == null ? "https://api.stripe.com" : stripeApi.strip().replaceAll("/+$", "");
        frontUrl = frontUrl == null ? "" : frontUrl.strip().replaceAll("/+$", "");
    }

    public boolean enabled() {
        return notBlank(secretKey) && notBlank(webhookSecret) && notBlank(priceEquipe) && notBlank(frontUrl);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
