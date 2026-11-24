package io.prsentinel.webhook;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Tâche réservée par un worker (avec son jeton de bail). */
public record ScanJob(long id, long installationId, String repoFullName, int prNumber, String headSha,
        String baseSha, int attempts, int maxAttempts, UUID lockToken, long generation, OffsetDateTime receivedAt) {}
