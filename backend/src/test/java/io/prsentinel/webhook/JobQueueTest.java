package io.prsentinel.webhook;

import static io.prsentinel.support.Payloads.BASE;
import static io.prsentinel.support.Payloads.SHA_A;
import static io.prsentinel.support.Payloads.SHA_B;
import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.support.PostgresTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JobQueueTest extends PostgresTestBase {

    static final Duration LEASE = Duration.ofSeconds(60);
    static final Duration ONE = Duration.ofSeconds(1);

    @Autowired
    JobQueue queue;

    static NewJob job(String delivery, String sha, int pr) {
        return new NewJob(delivery, 99, "acme/widgets", pr, sha, BASE);
    }

    static NewJob job(String delivery, String sha) {
        return job(delivery, sha, 7);
    }

    String status(long id) {
        return jdbc.sql("SELECT status FROM scan_job WHERE id = ?").param(id).query(String.class).single();
    }

    void expireLease(long id) {
        jdbc.sql("UPDATE scan_job SET locked_until = now() - interval '1 second' WHERE id = ?").param(id).update();
    }

    void makeReady(long id) {
        jdbc.sql("UPDATE scan_job SET run_after = now() WHERE id = ?").param(id).update();
    }

    List<String> statuses() {
        return jdbc.sql("SELECT head_sha || ':' || status FROM scan_job ORDER BY id").query(String.class).list();
    }

    @Test
    void claimEmptyQueue() {
        assertThat(queue.claim(LEASE)).isEmpty();
    }

    @Test
    void claimMarksRunningOnce() {
        long id = queue.enqueue(job("d-1", SHA_A), 3).jobId();
        ScanJob claimed = queue.claim(LEASE).orElseThrow();
        assertThat(claimed.id()).isEqualTo(id);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.baseSha()).isEqualTo(BASE);
        assertThat(status(id)).isEqualTo("running");
        assertThat(queue.claim(LEASE)).isEmpty();
    }

    @Test
    void concurrentWorkersNeverShareAJob() throws Exception {
        for (int i = 0; i < 20; i++) {
            queue.enqueue(job("d-" + i, "%040x".formatted(i), i + 1), 3);
        }
        List<Long> claimed = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier barrier = new CyclicBarrier(5);
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < 5; w++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    for (var j = queue.claim(LEASE); j.isPresent(); j = queue.claim(LEASE)) {
                        claimed.add(j.get().id());
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(claimed).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void complete() {
        queue.enqueue(job("d-1", SHA_A), 3);
        ScanJob j = queue.claim(LEASE).orElseThrow();
        assertThat(queue.complete(j)).isTrue();
        assertThat(status(j.id())).isEqualTo("done");
    }

    @Test
    void failRequeuesWithBackoff() {
        queue.enqueue(job("d-1", SHA_A), 3);
        ScanJob j = queue.claim(LEASE).orElseThrow();
        assertThat(queue.fail(j, "boom", Duration.ofSeconds(10), Duration.ofSeconds(100), false)).contains("queued");
        double delay = jdbc.sql("SELECT extract(epoch FROM run_after - now()) FROM scan_job").query(Double.class)
                .single();
        assertThat(delay).isBetween(8.0, 10.0);
        assertThat(queue.claim(LEASE)).isEmpty();
    }

    @Test
    void deadAfterMaxAttemptsOrPermanent() {
        long id = queue.enqueue(job("d-1", SHA_A), 2).jobId();
        for (String expected : List.of("queued", "dead")) {
            makeReady(id);
            assertThat(queue.fail(queue.claim(LEASE).orElseThrow(), "boom", ONE, ONE, false)).contains(expected);
        }
        makeReady(id);
        assertThat(queue.claim(LEASE)).isEmpty();

        queue.enqueue(job("d-2", SHA_B), 5);
        assertThat(queue.fail(queue.claim(LEASE).orElseThrow(), "404", ONE, ONE, true)).contains("dead");
    }

    @Test
    void errorTruncated() {
        queue.enqueue(job("d-1", SHA_A), 3);
        ScanJob j = queue.claim(LEASE).orElseThrow();
        queue.fail(j, "x".repeat(10_000), ONE, ONE, false);
        assertThat(jdbc.sql("SELECT length(last_error) FROM scan_job").query(Integer.class).single())
                .isEqualTo(JobQueue.MAX_ERROR_CHARS);
    }

    @Test
    void expiredLeaseReclaimedAndOldWorkerFenced() {
        queue.enqueue(job("d-1", SHA_A), 3);
        ScanJob first = queue.claim(LEASE).orElseThrow();
        expireLease(first.id());
        ScanJob second = queue.claim(LEASE).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.lockToken()).isNotEqualTo(first.lockToken());
        assertThat(queue.complete(first)).isFalse();
        assertThat(queue.fail(first, "late", ONE, ONE, false)).isEmpty();
        assertThat(queue.complete(second)).isTrue();
    }

    @Test
    void reapMarksExhaustedExpiredJobsDead() {
        queue.enqueue(job("d-1", SHA_A), 1);
        ScanJob j = queue.claim(LEASE).orElseThrow();
        expireLease(j.id());
        assertThat(queue.claim(LEASE)).isEmpty();
        assertThat(queue.reapExpired()).isEqualTo(1);
        assertThat(status(j.id())).isEqualTo("dead");
    }

    @Test
    void backoffIsExponentialAndCapped() {
        Duration ten = Duration.ofSeconds(10);
        Duration hundred = Duration.ofSeconds(100);
        assertThat(List.of(1, 2, 3, 4, 5, 60).stream().map(n -> JobQueue.backoff(n, ten, hundred).toSeconds()))
                .containsExactly(10L, 20L, 40L, 80L, 100L, 100L);
    }

    @Test
    void hasNewerJob() {
        queue.enqueue(job("d-1", SHA_A), 3);
        ScanJob old = queue.claim(LEASE).orElseThrow();
        assertThat(queue.hasNewerJob(old)).isFalse();
        queue.enqueue(job("d-2", SHA_B), 3);
        assertThat(queue.hasNewerJob(old)).isTrue();
    }

    @Test
    void supersedeNeverHitsANewerJob() {
        queue.enqueue(job("d-b", SHA_B), 3);
        jdbc.sql("UPDATE scan_job SET generation = 1000000 WHERE head_sha = ?").param(SHA_B).update();
        assertThat(queue.enqueue(job("d-a", SHA_A), 3).superseded()).isZero();
        assertThat(statuses()).containsExactly(SHA_B + ":queued", SHA_A + ":queued");
    }

    @Test
    void forcePushBackToSupersededShaRequeuesIt() {
        long first = queue.enqueue(job("d-1", SHA_A), 3).jobId();
        queue.enqueue(job("d-2", SHA_B), 3);
        JobQueue.EnqueueResult again = queue.enqueue(job("d-3", SHA_A), 3);
        assertThat(again.jobId()).isEqualTo(first);
        assertThat(again.superseded()).isEqualTo(1);
        assertThat(statuses()).containsExactly(SHA_A + ":queued", SHA_B + ":superseded");
    }

    @Test
    void reopenedDeadJobRescannedWithFreshAttempts() {
        queue.enqueue(job("d-1", SHA_A), 1);
        ScanJob j = queue.claim(LEASE).orElseThrow();
        assertThat(queue.fail(j, "boom", ONE, ONE, false)).contains("dead");
        assertThat(queue.enqueue(job("d-2", SHA_A), 3).jobId()).isEqualTo(j.id());
        assertThat(jdbc.sql("SELECT status || attempts || coalesce(last_error, '-') FROM scan_job")
                .query(String.class).single()).isEqualTo("queued0-");
    }

    @Test
    void sameShaWhileQueuedOrRunningIsDuplicate() {
        queue.enqueue(job("d-1", SHA_A), 3);
        assertThat(queue.enqueue(job("d-2", SHA_A), 3).duplicate()).isTrue();
        queue.claim(LEASE);
        assertThat(queue.enqueue(job("d-3", SHA_A), 3).duplicate()).isTrue();
    }

    @Test
    void concurrentIdenticalDeliveriesInsertOnce() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(4);
        List<Boolean> inserted = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    inserted.add(!queue.enqueue(job("same", SHA_A), 3).duplicate());
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(inserted.stream().filter(b -> b)).hasSize(1);
        assertThat(statuses()).hasSize(1);
    }

    @Test
    void runningJobNotSuperseded() {
        queue.enqueue(job("d-1", SHA_A), 3);
        ScanJob running = queue.claim(LEASE).orElseThrow();
        assertThat(queue.enqueue(job("d-2", SHA_B), 3).superseded()).isZero();
        assertThat(status(running.id())).isEqualTo("running");
    }
}
