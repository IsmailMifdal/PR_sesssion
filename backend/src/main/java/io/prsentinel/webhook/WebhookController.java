package io.prsentinel.webhook;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prsentinel.config.SentinelProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Réception des webhooks GitHub : signature, filtre d'événements, mise en file. Répond en quelques ms. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "sentinel.webhook", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SentinelProperties props;
    private final JobQueue queue;
    private final ObjectMapper mapper;

    public WebhookController(SentinelProperties props, JobQueue queue, ObjectMapper mapper) {
        this.props = props;
        this.queue = queue;
        this.mapper = mapper;
        props.webhook().secretBytes(); // échoue au démarrage si le secret est faible ou absent
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws IOException {
        int limit = props.webhook().maxPayloadBytes();
        if (request.getContentLengthLong() > limit) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload trop volumineux");
        }
        byte[] body = readBounded(request.getInputStream(), limit);
        if (body == null) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload trop volumineux");
        }
        if (!WebhookSignature.verify(props.webhook().secretBytes(), body,
                request.getHeader("X-Hub-Signature-256"))) {
            log.warn("webhook rejeté : signature invalide");
            return error(HttpStatus.UNAUTHORIZED, "signature invalide");
        }

        String event = String.valueOf(request.getHeader("X-GitHub-Event"));
        if (event.equals("ping")) {
            return ResponseEntity.ok(Map.of("status", "pong"));
        }
        if (!event.equals("pull_request")) {
            return accepted(Map.of("status", "ignored"));
        }

        NewJob job;
        try {
            var parsed = PullRequestEventParser.parse(request.getHeader("X-GitHub-Delivery"), mapper.readTree(body));
            if (parsed.isEmpty()) {
                return accepted(Map.of("status", "ignored"));
            }
            job = parsed.get();
        } catch (JacksonException | InvalidPayloadException e) {
            return error(HttpStatus.BAD_REQUEST, "payload invalide");
        }

        JobQueue.EnqueueResult result = queue.enqueue(job, props.queue().maxAttempts());
        if (result.duplicate()) {
            return accepted(Map.of("status", "duplicate"));
        }
        log.info("tâche {} en file pour {}#{} @ {}", result.jobId(), job.repoFullName(), job.prNumber(),
                job.headSha().substring(0, 12));
        return accepted(Map.of("status", "queued", "job_id", result.jobId()));
    }

    /** Lit au plus {@code limit} octets ; {@code null} si le corps est plus gros. */
    static byte[] readBounded(InputStream in, int limit) throws IOException {
        byte[] data = in.readNBytes(limit + 1);
        return data.length > limit ? null : data;
    }

    private static ResponseEntity<Map<String, Object>> accepted(Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
