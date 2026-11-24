package io.prsentinel.billing;

import java.util.Set;

/** Offres et lecture des statuts Stripe. */
public final class Plans {

    public static final String DECOUVERTE = "DECOUVERTE";
    public static final String EQUIPE = "EQUIPE";

    /**
     * Statuts Stripe qui donnent accès à l'offre payante. {@code past_due} : Stripe relance le paiement ;
     * l'accès est maintenu pendant les relances, puis l'abonnement passe en {@code canceled} ou {@code unpaid}.
     */
    static final Set<String> PAID = Set.of("active", "trialing", "past_due");

    private Plans() {}

    public static boolean isPaid(String stripeStatus) {
        return stripeStatus != null && PAID.contains(stripeStatus);
    }
}
