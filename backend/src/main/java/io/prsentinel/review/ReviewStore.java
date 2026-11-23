package io.prsentinel.review;

import io.prsentinel.scan.Finding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistance des revues, alertes et verdicts. */
@Repository
public class ReviewStore {

    private final JdbcClient jdbc;

    public ReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Alerte enregistrée, avec son identifiant en base. */
    public record StoredFinding(long id, Finding finding) {}

    public long startReview(long githubInstallationId, String repoFullName, int pr, String sha) {
        long installation = jdbc.sql("""
                INSERT INTO installation (github_installation_id, compte) VALUES (?, ?)
                ON CONFLICT (github_installation_id) DO UPDATE SET compte = EXCLUDED.compte
                RETURNING id""")
                .params(githubInstallationId, repoFullName.substring(0, repoFullName.indexOf('/')))
                .query(Long.class).single();
        long repository = jdbc.sql("""
                INSERT INTO repository (installation_id, nom_complet) VALUES (?, ?)
                ON CONFLICT (nom_complet) DO UPDATE SET installation_id = EXCLUDED.installation_id
                RETURNING id""").params(installation, repoFullName).query(Long.class).single();
        return jdbc.sql("""
                INSERT INTO review (repository_id, pr_numero, commit_sha) VALUES (?, ?, ?) RETURNING id""")
                .params(repository, pr, sha).query(Long.class).single();
    }

    public List<StoredFinding> saveFindings(long reviewId, List<Finding> findings, List<String> hashes) {
        List<StoredFinding> stored = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            long id = jdbc.sql("""
                    INSERT INTO finding (review_id, outil, regle, fichier, ligne, gravite_outil, message, extrait_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(reviewId, f.tool(), f.ruleId(), f.path(), f.line(), f.severity(), f.message(),
                            hashes.get(i))
                    .query(Long.class).single();
            stored.add(new StoredFinding(id, f));
        }
        return stored;
    }

    public void saveVerdict(long findingId, String decision, String gravite, String explication, String correctif,
            double confiance, String modele) {
        jdbc.sql("""
                INSERT INTO verdict (finding_id, decision, gravite, explication, correctif, confiance, modele)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(findingId, decision, gravite, explication, correctif, BigDecimal.valueOf(confiance), modele)
                .update();
    }

    public void finish(long reviewId, String statut, long tokensIn, long tokensOut, BigDecimal costUsd,
            boolean blocking, String error) {
        jdbc.sql("""
                UPDATE review SET statut = ?, fin = now(), tokens_entree = ?, tokens_sortie = ?, cout_usd = ?,
                       bloquante = ?, erreur = ?
                WHERE id = ?""")
                .params(statut, tokensIn, tokensOut, costUsd, blocking, error, reviewId).update();
    }

    /** Une revue a déjà été publiée pour ce commit : un nouvel essai ne doit pas la republier. */
    public boolean alreadyPublished(String repoFullName, int pr, String sha) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM review r JOIN repository p ON p.id = r.repository_id
                    WHERE p.nom_complet = ? AND r.pr_numero = ? AND r.commit_sha = ? AND r.publiee)""")
                .params(repoFullName, pr, sha).query(Boolean.class).single();
    }

    public void markPublished(long reviewId) {
        jdbc.sql("UPDATE review SET publiee = true WHERE id = ?").param(reviewId).update();
    }

    /** Revues restées EN_COURS au-delà du bail (worker arrêté) : passées en ECHEC. */
    public int reapStale(java.time.Duration lease) {
        return jdbc.sql("""
                UPDATE review SET statut = 'ECHEC', fin = now(), erreur = coalesce(erreur, 'revue interrompue')
                WHERE statut = 'EN_COURS' AND debut < now() - make_interval(secs => ?)""")
                .param((double) lease.toSeconds()).update();
    }

    /** Coût IA déjà dépensé aujourd'hui (UTC) pour ce dépôt : sert au plafond quotidien. */
    public BigDecimal costToday(String repoFullName) {
        return jdbc.sql("""
                SELECT coalesce(sum(r.cout_usd), 0) FROM review r JOIN repository p ON p.id = r.repository_id
                WHERE p.nom_complet = ? AND r.debut >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """).param(repoFullName).query(BigDecimal.class).single();
    }
}
