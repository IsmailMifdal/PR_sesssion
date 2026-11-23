package io.prsentinel.webhook;

/** Tâche de scan à créer à partir d'un événement {@code pull_request}. */
public record NewJob(String deliveryId, long installationId, String repoFullName, int prNumber, String headSha,
        String baseSha) {}
