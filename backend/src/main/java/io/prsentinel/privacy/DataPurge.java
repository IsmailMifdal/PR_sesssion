package io.prsentinel.privacy;

import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effacement des données (RGPD) : durée de conservation, désinstallation de l'app, suppression de compte.
 * Les alertes, verdicts et avis suivent leur revue (clés étrangères en cascade).
 */
@Component
public class DataPurge {

    private static final Logger log = LoggerFactory.getLogger(DataPurge.class);

    private final JdbcClient jdbc;

    public DataPurge(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Purged(int reviews, int jobs) {}

    /** Revues (et tout ce qui en dépend) plus anciennes que {@code days} jours ; tâches terminées depuis 30 jours. */
    @Transactional
    public Purged olderThan(int days) {
        int reviews = jdbc.sql("DELETE FROM review WHERE debut < now() - make_interval(days => ?)").param(days).update();
        int jobs = jdbc.sql("""
                DELETE FROM scan_job WHERE status IN ('done', 'dead', 'superseded')
                  AND updated_at < now() - interval '30 days'""").update();
        jdbc.sql("DELETE FROM user_session WHERE expire_le < now()").update();
        jdbc.sql("DELETE FROM login_code WHERE expire_le < now()").update();
        return new Purged(reviews, jobs);
    }

    /** L'app a été désinstallée : toutes les données de ce compte GitHub disparaissent. */
    @Transactional
    public void installation(long githubInstallationId) {
        jdbc.sql("DELETE FROM scan_job WHERE installation_id = ?").param(githubInstallationId).update();
        int n = jdbc.sql("DELETE FROM installation WHERE github_installation_id = ?").param(githubInstallationId).update();
        jdbc.sql("DELETE FROM user_installation WHERE github_installation_id = ?").param(githubInstallationId).update();
        log.info("installation {} désinstallée : données effacées ({} installation)", githubInstallationId, n);
    }

    /** Dépôts retirés de l'installation : leurs revues et tâches disparaissent. */
    @Transactional
    public void repositories(Collection<String> fullNames) {
        for (String repo : fullNames) {
            jdbc.sql("DELETE FROM scan_job WHERE repo_full_name = ?").param(repo).update();
            jdbc.sql("DELETE FROM repository WHERE nom_complet = ?").param(repo).update();
            jdbc.sql("DELETE FROM user_repo WHERE depot = ?").param(repo).update();
        }
        log.info("{} dépôt(s) retiré(s) de l'installation : données effacées", fullNames.size());
    }

    /** Droit à l'effacement : le compte, ses sessions, ses liens aux dépôts et ses avis. */
    @Transactional
    public void user(long userId) {
        jdbc.sql("DELETE FROM verdict_feedback WHERE auteur_cle = ?").param("u:" + userId).update();
        jdbc.sql("DELETE FROM app_user WHERE id = ?").param(userId).update();
        log.info("compte utilisateur {} supprimé à sa demande", userId);
    }
}
