package io.prsentinel.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applique chaque nuit la durée de conservation ({@code SENTINEL_RETENTION_DAYS}, 365 jours par défaut).
 * Idempotent : sans effet si un autre rôle (API ou worker) l'a déjà fait.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final DataPurge purge;
    private final int days;

    public RetentionJob(DataPurge purge, @Value("${sentinel.privacy.retention-days:365}") int days) {
        this.purge = purge;
        this.days = days;
    }

    @Scheduled(cron = "${sentinel.privacy.retention-cron:0 17 3 * * *}", zone = "UTC")
    public void run() {
        if (days <= 0) {
            return; // 0 : conservation illimitée (choix explicite de l'opérateur)
        }
        try {
            var p = purge.olderThan(days);
            if (p.reviews() + p.jobs() > 0) {
                log.info("conservation : {} revue(s) et {} tâche(s) de plus de {} jours effacées", p.reviews(), p.jobs(),
                        days);
            }
        } catch (RuntimeException e) {
            log.error("purge de conservation en échec", e);
        }
    }

    public int days() {
        return days;
    }
}
