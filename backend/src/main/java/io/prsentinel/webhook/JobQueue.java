package io.prsentinel.webhook;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * File de tâches PostgreSQL : réservation {@code FOR UPDATE SKIP LOCKED}, bail avec jeton
 * (un worker dont le bail a été repris ne peut plus écrire), backoff exponentiel, dead-letter.
 */
@Repository
public class JobQueue {

    public static final int MAX_ERROR_CHARS = 2000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JobQueue(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record EnqueueResult(Long jobId, int superseded) {
        public boolean duplicate() {
            return jobId == null;
        }
    }

    /**
     * Met une tâche en file de façon idempotente : même livraison, ou même sha déjà en file ou en cours,
     * donne un doublon ; un sha déjà traité, remplacé ou mort est remis en file ; les tâches en attente
     * plus anciennes de la même PR passent à {@code superseded}.
     */
    public EnqueueResult enqueue(NewJob job, int maxAttempts) {
        try {
            return tx.execute(status -> {
                boolean seen = jdbc.sql("SELECT EXISTS (SELECT 1 FROM scan_job WHERE delivery_id = ?)")
                        .param(job.deliveryId()).query(Boolean.class).single();
                if (seen) {
                    return new EnqueueResult(null, 0);
                }
                Optional<long[]> row = jdbc.sql("""
                        INSERT INTO scan_job (delivery_id, installation_id, repo_full_name, pr_number,
                                              head_sha, base_sha, max_attempts)
                        VALUES (:delivery, :installation, :repo, :pr, :head, :base, :max)
                        ON CONFLICT (repo_full_name, pr_number, head_sha) DO UPDATE
                        SET status = 'queued', attempts = 0, run_after = now(), last_error = NULL,
                            lock_token = NULL, locked_until = NULL,
                            delivery_id = EXCLUDED.delivery_id, installation_id = EXCLUDED.installation_id,
                            base_sha = EXCLUDED.base_sha, max_attempts = EXCLUDED.max_attempts,
                            generation = nextval('scan_job_generation_seq'), received_at = now(), updated_at = now()
                        WHERE scan_job.status IN ('done', 'dead', 'superseded')
                        RETURNING id, generation""")
                        .param("delivery", job.deliveryId())
                        .param("installation", job.installationId())
                        .param("repo", job.repoFullName())
                        .param("pr", job.prNumber())
                        .param("head", job.headSha())
                        .param("base", job.baseSha())
                        .param("max", maxAttempts)
                        .query((rs, i) -> new long[] {rs.getLong("id"), rs.getLong("generation")})
                        .optional();
                if (row.isEmpty()) {
                    return new EnqueueResult(null, 0);
                }
                int superseded = jdbc.sql("""
                        UPDATE scan_job SET status = 'superseded', updated_at = now()
                        WHERE repo_full_name = ? AND pr_number = ? AND status = 'queued' AND generation < ?""")
                        .params(job.repoFullName(), job.prNumber(), row.get()[1]).update();
                return new EnqueueResult(row.get()[0], superseded);
            });
        } catch (DuplicateKeyException e) {
            return new EnqueueResult(null, 0); // même livraison reçue deux fois en parallèle
        }
    }

    /** Passe en {@code dead} les tâches au bail expiré qui ont épuisé leurs tentatives. */
    public int reapExpired() {
        return jdbc.sql("""
                UPDATE scan_job
                SET status = 'dead', lock_token = NULL, locked_until = NULL, updated_at = now(),
                    last_error = coalesce(last_error, 'bail expiré après la dernière tentative')
                WHERE status = 'running' AND locked_until < now() AND attempts >= max_attempts""").update();
    }

    public Optional<ScanJob> claim(Duration lease) {
        return jdbc.sql("""
                WITH next AS (
                    SELECT id FROM scan_job
                    WHERE (status = 'queued' AND run_after <= now())
                       OR (status = 'running' AND locked_until < now() AND attempts < max_attempts)
                    ORDER BY run_after, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE scan_job j
                SET status = 'running', attempts = j.attempts + 1, lock_token = ?,
                    locked_until = now() + make_interval(secs => ?), updated_at = now()
                FROM next WHERE j.id = next.id
                RETURNING j.*""")
                .params(UUID.randomUUID(), (double) lease.toSeconds())
                .query((rs, i) -> new ScanJob(rs.getLong("id"), rs.getLong("installation_id"),
                        rs.getString("repo_full_name"), rs.getInt("pr_number"), rs.getString("head_sha"),
                        rs.getString("base_sha"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                        rs.getObject("lock_token", UUID.class), rs.getLong("generation"),
                        rs.getObject("received_at", java.time.OffsetDateTime.class)))
                .optional();
    }

    /** {@code false} si le bail a été repris par un autre worker. */
    public boolean complete(ScanJob job) {
        return jdbc.sql("""
                UPDATE scan_job SET status = 'done', lock_token = NULL, locked_until = NULL,
                       last_error = NULL, updated_at = now()
                WHERE id = ? AND status = 'running' AND lock_token = ?""")
                .params(job.id(), job.lockToken()).update() == 1;
    }

    public static Duration backoff(int attempts, Duration base, Duration max) {
        long factor = 1L << Math.min(30, Math.max(0, attempts - 1));
        Duration delay = base.multipliedBy(factor);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    /** Replanifie avec backoff, ou passe en {@code dead} ; vide si le bail a été perdu. */
    public Optional<String> fail(ScanJob job, String error, Duration base, Duration max, boolean permanent) {
        String trimmed = error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) : error;
        return jdbc.sql("""
                UPDATE scan_job
                SET status = CASE WHEN ? OR attempts >= max_attempts THEN 'dead' ELSE 'queued' END,
                    run_after = now() + make_interval(secs => ?),
                    lock_token = NULL, locked_until = NULL, last_error = ?, updated_at = now()
                WHERE id = ? AND status = 'running' AND lock_token = ?
                RETURNING status""")
                .params(permanent, (double) backoff(job.attempts(), base, max).toSeconds(), trimmed, job.id(),
                        job.lockToken())
                .query(String.class).optional();
    }

    /** Vrai si une tâche plus récente et encore utile existe pour la même PR. */
    public boolean hasNewerJob(ScanJob job) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM scan_job
                    WHERE repo_full_name = ? AND pr_number = ? AND generation > ?
                      AND status IN ('queued', 'running', 'done'))""")
                .params(job.repoFullName(), job.prNumber(), job.generation()).query(Boolean.class).single();
    }
}
