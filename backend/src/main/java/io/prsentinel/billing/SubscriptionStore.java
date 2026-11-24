package io.prsentinel.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Abonnements (recopiés de Stripe), consommation mensuelle et comptes que l'utilisateur peut gérer. */
@Component
public class SubscriptionStore {

    private final JdbcClient jdbc;

    public SubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Subscription(long githubInstallationId, String customerId, String subscriptionId, String statut,
            int sieges, OffsetDateTime finPeriode, boolean annulationFinPeriode) {

        public String offre() {
            return Plans.isPaid(statut) ? Plans.EQUIPE : Plans.DECOUVERTE;
        }
    }

    public record Account(long githubInstallationId, String compte) {}

    public Optional<Subscription> find(long githubInstallationId) {
        return jdbc.sql("""
                SELECT github_installation_id, stripe_customer_id, stripe_subscription_id, statut, sieges, fin_periode,
                       annulation_fin_periode
                FROM subscription WHERE github_installation_id = ?""").param(githubInstallationId)
                .query((rs, i) -> new Subscription(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getObject(6, OffsetDateTime.class), rs.getBoolean(7)))
                .optional();
    }

    /**
     * Recopie l'état d'un abonnement Stripe. Un événement en retard sur un ancien abonnement résilié ne doit pas
     * écraser un abonnement actif plus récent : la mise à jour n'a lieu que pour le même abonnement, ou si
     * l'abonnement enregistré ne donne plus accès à l'offre payante.
     */
    public boolean upsert(long githubInstallationId, JsonNode sub) {
        JsonNode item = sub.path("items").path("data").path(0);
        long end = sub.path("current_period_end").asLong(item.path("current_period_end").asLong(0));
        return jdbc.sql("""
                INSERT INTO subscription (github_installation_id, stripe_customer_id, stripe_subscription_id, statut,
                                          sieges, fin_periode, annulation_fin_periode)
                VALUES (:inst, :customer, :sub, :statut, :sieges, :fin, :annul)
                ON CONFLICT (github_installation_id) DO UPDATE SET
                    stripe_customer_id = excluded.stripe_customer_id,
                    stripe_subscription_id = excluded.stripe_subscription_id, statut = excluded.statut,
                    sieges = excluded.sieges, fin_periode = excluded.fin_periode,
                    annulation_fin_periode = excluded.annulation_fin_periode, maj_le = now()
                WHERE subscription.stripe_subscription_id = excluded.stripe_subscription_id
                   OR subscription.statut NOT IN ('active', 'trialing', 'past_due')""")
                .param("inst", githubInstallationId)
                .param("customer", sub.path("customer").asText())
                .param("sub", sub.path("id").asText())
                .param("statut", sub.path("status").asText())
                .param("sieges", Math.max(1, item.path("quantity").asInt(1)))
                .param("fin", end > 0 ? Instant.ofEpochSecond(end).atOffset(ZoneOffset.UTC) : null)
                .param("annul", sub.path("cancel_at_period_end").asBoolean(false))
                .update() == 1;
    }

    /** Revues lancées depuis le 1er du mois (UTC) sur les dépôts de l'installation (hors revues refusées). */
    public int reviewsThisMonth(long githubInstallationId) {
        return jdbc.sql("""
                SELECT count(*) FROM review r JOIN repository p ON p.id = r.repository_id
                JOIN installation i ON i.id = p.installation_id
                WHERE i.github_installation_id = ? AND r.statut <> 'IGNOREE'
                  AND r.debut >= date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'""")
                .param(githubInstallationId).query(Integer.class).single();
    }

    /** Comptes visibles : ceux de l'utilisateur (relevés à la connexion), ou tous pour l'opérateur. */
    public List<Account> accounts(boolean admin, long userId) {
        if (admin) {
            return jdbc.sql("SELECT github_installation_id, compte FROM installation ORDER BY compte")
                    .query((rs, i) -> new Account(rs.getLong(1), rs.getString(2))).list();
        }
        return jdbc.sql("""
                SELECT github_installation_id, compte FROM user_installation WHERE user_id = ? ORDER BY compte""")
                .param(userId).query((rs, i) -> new Account(rs.getLong(1), rs.getString(2))).list();
    }

    public Optional<Account> account(boolean admin, long userId, long githubInstallationId) {
        return accounts(admin, userId).stream().filter(a -> a.githubInstallationId() == githubInstallationId)
                .findFirst();
    }
}
