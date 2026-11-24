package io.prsentinel.notify;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Réglages de notification par installation, et chiffres des revues pour les messages. */
@Component
public class NotificationStore {

    public static final String CRITIQUE = "CRITIQUE";
    public static final String VRAI_PROBLEME = "VRAI_PROBLEME";

    private final JdbcClient jdbc;

    public NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Settings(long githubInstallationId, String slackSealed, String slackHint, String slackLevel,
            List<String> emails, boolean weekly) {

        static Settings defaults(long id) {
            return new Settings(id, null, null, CRITIQUE, List.of(), true);
        }
    }

    public Settings get(long githubInstallationId) {
        return jdbc.sql("""
                SELECT github_installation_id, slack_webhook_chiffre, slack_indice, slack_niveau, emails, rapport_hebdo
                FROM notification_settings WHERE github_installation_id = ?""").param(githubInstallationId)
                .query((rs, i) -> new Settings(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        strings(rs.getArray(5)), rs.getBoolean(6)))
                .optional().orElse(Settings.defaults(githubInstallationId));
    }

    public void save(Settings s, String by) {
        jdbc.sql("""
                INSERT INTO notification_settings (github_installation_id, slack_webhook_chiffre, slack_indice,
                    slack_niveau, emails, rapport_hebdo, maj_par)
                VALUES (:id, :sealed, :hint, :level, :emails, :weekly, :by)
                ON CONFLICT (github_installation_id) DO UPDATE SET slack_webhook_chiffre = excluded.slack_webhook_chiffre,
                    slack_indice = excluded.slack_indice, slack_niveau = excluded.slack_niveau, emails = excluded.emails,
                    rapport_hebdo = excluded.rapport_hebdo, maj_par = excluded.maj_par, maj_le = now()""")
                .param("id", s.githubInstallationId()).param("sealed", s.slackSealed()).param("hint", s.slackHint())
                .param("level", s.slackLevel()).param("emails", s.emails().toArray(new String[0]))
                .param("weekly", s.weekly()).param("by", by).update();
    }

    /** Installations ayant au moins un destinataire et le rapport hebdomadaire activé. */
    public List<Settings> weeklyRecipients() {
        return jdbc.sql("""
                SELECT github_installation_id, slack_webhook_chiffre, slack_indice, slack_niveau, emails, rapport_hebdo
                FROM notification_settings WHERE rapport_hebdo AND cardinality(emails) > 0""")
                .query((rs, i) -> new Settings(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        strings(rs.getArray(5)), rs.getBoolean(6)))
                .list();
    }

    /** Réserve l'envoi du rapport de cette semaine : vrai pour une seule instance, une seule fois. */
    public boolean claimWeekly(long githubInstallationId, LocalDate monday) {
        return jdbc.sql("""
                INSERT INTO weekly_report_sent (github_installation_id, semaine) VALUES (?, ?) ON CONFLICT DO NOTHING""")
                .params(githubInstallationId, monday).update() == 1;
    }

    /** Résumé d'une revue pour l'alerte Slack. */
    public record ReviewFacts(long reviewId, long githubInstallationId, String repo, int pr, boolean blocking,
            int critical, int high, int medium, int low, String topRule, String topLocation) {

        public int issues() {
            return critical + high + medium + low;
        }
    }

    public Optional<ReviewFacts> review(long reviewId) {
        return jdbc.sql("""
                SELECT r.id, i.github_installation_id, p.nom_complet, r.pr_numero, r.bloquante,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'CRITIQUE') AS c,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'HAUTE') AS h,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'MOYENNE') AS m,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'BASSE') AS l,
                       (array_agg(f.regle ORDER BY CASE v.gravite WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1
                            WHEN 'MOYENNE' THEN 2 ELSE 3 END) FILTER (WHERE v.decision = 'VRAI_PROBLEME'))[1] AS regle,
                       (array_agg(f.fichier || coalesce(':' || f.ligne, '') ORDER BY CASE v.gravite WHEN 'CRITIQUE' THEN 0
                            WHEN 'HAUTE' THEN 1 WHEN 'MOYENNE' THEN 2 ELSE 3 END)
                            FILTER (WHERE v.decision = 'VRAI_PROBLEME'))[1] AS emplacement
                FROM review r JOIN repository p ON p.id = r.repository_id JOIN installation i ON i.id = p.installation_id
                LEFT JOIN finding f ON f.review_id = r.id LEFT JOIN verdict v ON v.finding_id = f.id
                WHERE r.id = ? GROUP BY r.id, i.github_installation_id, p.nom_complet""").param(reviewId)
                .query((rs, i) -> new ReviewFacts(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getBoolean(5), rs.getInt("c"), rs.getInt("h"), rs.getInt("m"), rs.getInt("l"),
                        rs.getString("regle"), rs.getString("emplacement")))
                .optional();
    }

    /** Chiffres d'une installation sur une période, pour le rapport hebdomadaire. */
    public record Week(int reviews, int blocking, int critical, int high, int medium, int low, int dismissed,
            double costUsd, List<String> topRepos) {

        public int issues() {
            return critical + high + medium + low;
        }
    }

    public Week week(long githubInstallationId, LocalDate from, LocalDate to) {
        var totals = jdbc.sql("""
                SELECT count(DISTINCT r.id) AS revues,
                       count(DISTINCT r.id) FILTER (WHERE r.bloquante) AS bloquantes,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'CRITIQUE') AS c,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'HAUTE') AS h,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'MOYENNE') AS m,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME' AND v.gravite = 'BASSE') AS l,
                       count(v.id) FILTER (WHERE v.decision = 'FAUX_POSITIF') AS ecartees
                FROM review r JOIN repository p ON p.id = r.repository_id JOIN installation i ON i.id = p.installation_id
                LEFT JOIN finding f ON f.review_id = r.id LEFT JOIN verdict v ON v.finding_id = f.id
                WHERE i.github_installation_id = :id AND r.statut <> 'IGNOREE'
                  AND r.debut >= :from::date AND r.debut < :to::date""")
                .param("id", githubInstallationId).param("from", from).param("to", to).query().singleRow();
        double cost = jdbc.sql("""
                SELECT coalesce(sum(r.cout_usd), 0) FROM review r JOIN repository p ON p.id = r.repository_id
                JOIN installation i ON i.id = p.installation_id
                WHERE i.github_installation_id = :id AND r.debut >= :from::date AND r.debut < :to::date""")
                .param("id", githubInstallationId).param("from", from).param("to", to).query(Double.class).single();
        List<String> top = jdbc.sql("""
                SELECT p.nom_complet || ' (' || count(v.id) || ')' FROM review r JOIN repository p ON p.id = r.repository_id
                JOIN installation i ON i.id = p.installation_id JOIN finding f ON f.review_id = r.id
                JOIN verdict v ON v.finding_id = f.id AND v.decision = 'VRAI_PROBLEME'
                WHERE i.github_installation_id = :id AND r.debut >= :from::date AND r.debut < :to::date
                GROUP BY p.nom_complet ORDER BY count(v.id) DESC, p.nom_complet LIMIT 5""")
                .param("id", githubInstallationId).param("from", from).param("to", to).query(String.class).list();
        return new Week(n(totals.get("revues")), n(totals.get("bloquantes")), n(totals.get("c")), n(totals.get("h")),
                n(totals.get("m")), n(totals.get("l")), n(totals.get("ecartees")), cost, top);
    }

    private static int n(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }
}
