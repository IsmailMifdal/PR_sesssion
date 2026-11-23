package io.prsentinel.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration de PR Sentinel (préfixe {@code sentinel.*}, variables {@code SENTINEL_*}). */
@Validated
@ConfigurationProperties(prefix = "sentinel")
public record SentinelProperties(
        @Valid @NotNull Webhook webhook,
        @Valid @NotNull Github github,
        @Valid @NotNull Scan scan,
        @Valid @NotNull Queue queue,
        @Valid @NotNull Agent agent) {

    public static final int MIN_WEBHOOK_SECRET_CHARS = 32;

    public SentinelProperties {
        Duration worstCase = scan.cloneTimeout().multipliedBy(4)
                .plus(scan.scannerTimeout().plusSeconds(30).multipliedBy(3));
        if (queue.lease().compareTo(worstCase.plus(agent.timeBudget())) <= 0) {
            throw new IllegalArgumentException("sentinel.queue.lease (" + queue.lease()
                    + ") doit dépasser la durée maximale d'une revue (" + worstCase.plus(agent.timeBudget()) + ")");
        }
    }

    /** Secret partagé avec GitHub ; seul le rôle API en a besoin. */
    public record Webhook(String secret, @Positive int maxPayloadBytes) {

        public byte[] secretBytes() {
            if (secret == null || secret.length() < MIN_WEBHOOK_SECRET_CHARS
                    || secret.toLowerCase().contains("change-me")) {
                throw new IllegalStateException("SENTINEL_WEBHOOK_SECRET doit être aléatoire et faire au moins "
                        + MIN_WEBHOOK_SECRET_CHARS + " caractères (openssl rand -hex 32)");
            }
            return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public record Github(long appId, String privateKey, @NotBlank String apiUrl, @NotBlank String gitUrl,
            @NotBlank String checkName) {}

    public record Scan(@NotBlank String semgrepConfig, @NotBlank String semgrepBin, @NotBlank String gitleaksBin,
            @NotBlank String trivyBin, @NotNull Duration scannerTimeout, @NotNull Duration cloneTimeout,
            @Positive long maxReportBytes, boolean trivyOfflineDb) {}

    public record Queue(@NotNull Duration lease, @Min(1) int maxAttempts, @NotNull Duration backoffBase,
            @NotNull Duration backoffMax, @NotNull Duration pollInterval, boolean workerEnabled) {}

    public record Agent(boolean enabled, @NotBlank String model, @NotBlank String strongModel,
            @Min(1) int maxFindingsPerPr, @Min(1) int maxToolCalls, @NotNull BigDecimal dailyBudgetUsdPerRepo,
            @NotNull BigDecimal inputPricePerMillion, @NotNull BigDecimal outputPricePerMillion,
            @NotNull BigDecimal strongInputPricePerMillion, @NotNull BigDecimal strongOutputPricePerMillion,
            @NotNull Duration timeBudget) {}
}
