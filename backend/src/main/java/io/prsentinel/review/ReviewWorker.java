package io.prsentinel.review;

import io.prsentinel.config.SentinelProperties;
import io.prsentinel.github.GitHubException;
import io.prsentinel.webhook.JobQueue;
import io.prsentinel.webhook.ScanJob;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Boucle du worker : réserve une tâche, la traite, la termine ou la replanifie. */
public class ReviewWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);

    private final JobQueue queue;
    private final ReviewPipeline pipeline;
    private final SentinelProperties props;
    private volatile boolean running;
    private Thread thread;

    public ReviewWorker(JobQueue queue, ReviewPipeline pipeline, SentinelProperties props) {
        this.queue = queue;
        this.pipeline = pipeline;
        this.props = props;
    }

    /** Traite au plus une tâche ; {@code false} si la file était vide. */
    public boolean runOnce() {
        queue.reapExpired();
        Optional<ScanJob> claimed = queue.claim(props.queue().lease());
        if (claimed.isEmpty()) {
            return false;
        }
        ScanJob job = claimed.get();
        log.info("tâche {} : {}#{} @ {} (tentative {}/{})", job.id(), job.repoFullName(), job.prNumber(),
                job.headSha().substring(0, 12), job.attempts(), job.maxAttempts());
        try {
            pipeline.process(job);
            if (!queue.complete(job)) {
                log.warn("tâche {} : bail perdu avant la fin", job.id());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, e, false);
        } catch (Exception e) {
            boolean permanent = e instanceof GitHubException g && g.isPermanent();
            if (e instanceof GitHubException g && g.isUnauthorized()) {
                pipeline.github().invalidateToken(job.installationId(), job.repoFullName());
            }
            fail(job, e, permanent);
        }
        return true;
    }

    private void fail(ScanJob job, Exception e, boolean permanent) {
        Optional<String> status = queue.fail(job, e.getClass().getSimpleName() + ": " + e.getMessage(),
                props.queue().backoffBase(), props.queue().backoffMax(), permanent);
        log.warn("tâche {} en échec ({}) → {}", job.id(), e.getMessage(), status.orElse("bail perdu"));
    }

    private void loop() {
        while (running) {
            boolean worked;
            try {
                worked = runOnce();
            } catch (RuntimeException e) {
                log.error("erreur de la boucle du worker", e);
                worked = false;
            }
            if (!worked) {
                try {
                    Thread.sleep(props.queue().pollInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("review-worker").start(this::loop);
    }

    /** Laisse finir la tâche en cours (sinon elle resterait réservée jusqu'à la fin du bail). */
    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(props.queue().lease());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
