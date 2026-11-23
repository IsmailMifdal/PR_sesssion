package io.prsentinel.agent;

import io.prsentinel.config.SentinelProperties;
import io.prsentinel.diff.PullRequestDiff;
import io.prsentinel.scan.Finding;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triage des alertes d'une PR, dans l'ordre donné (les plus graves d'abord) : plafond d'alertes, budget,
 * échéance, masquage des secrets, second avis du modèle fort. Partagé par la publication GitHub et par le
 * banc d'évaluation.
 *
 * <p>Pour qu'une injection de prompt ne puisse pas faire taire une vraie faille avec le petit modèle, un
 * FAUX_POSITIF sur une alerte grave (CRITICAL, HIGH, ERROR, SECRET) doit être confirmé par le modèle fort.
 */
public class TriageSession {

    private static final Logger log = LoggerFactory.getLogger(TriageSession.class);
    static final int EXCERPT_CONTEXT = 8;
    public static final Set<String> HIGH_TOOL_SEVERITIES = Set.of("CRITICAL", "HIGH", "ERROR", "SECRET");

    /** {@code triages.get(i)} est {@code null} si l'alerte i n'a pas été triée (plafond, budget, échéance, réponse invalide). */
    public record Result(List<Triage> triages, long tokensIn, long tokensOut, BigDecimal costUsd, boolean stoppedEarly) {}

    private final SentinelProperties.Agent cfg;
    private final TriageAgent agent;
    private final Clock clock;

    public TriageSession(SentinelProperties.Agent cfg, TriageAgent agent) {
        this(cfg, agent, Clock.systemUTC());
    }

    public TriageSession(SentinelProperties.Agent cfg, TriageAgent agent, Clock clock) {
        this.cfg = cfg;
        this.agent = agent;
        this.clock = clock;
    }

    public static boolean isHighSeverity(Finding f) {
        return HIGH_TOOL_SEVERITIES.contains(f.severity());
    }

    public Result run(List<Finding> ordered, Path source, PullRequestDiff diff, BigDecimal budgetLeft, UsageMeter meter) {
        Instant deadline = clock.instant().plus(cfg.timeBudget());
        SecretMasker masker = new SecretMasker(secretLines(ordered));
        BigDecimal startCost = meter.cost();
        List<Triage> triages = new ArrayList<>();
        int analysed = 0;
        boolean stoppedEarly = false;
        for (Finding f : ordered) {
            boolean overBudget = meter.cost().subtract(startCost).compareTo(budgetLeft) >= 0;
            boolean late = clock.instant().isAfter(deadline);
            if (analysed >= cfg.maxFindingsPerPr() || overBudget || late) {
                stoppedEarly |= overBudget || late;
                triages.add(null);
                continue;
            }
            analysed++;
            triages.add(triageWithEscalation(f, source, diff, masker, meter));
        }
        BigDecimal spent = meter.cost().subtract(startCost);
        return new Result(triages, meter.tokensIn(), meter.tokensOut(), spent, stoppedEarly);
    }

    private Triage triageWithEscalation(Finding f, Path source, PullRequestDiff diff, SecretMasker masker,
            UsageMeter meter) {
        try {
            Triage t = triageOnce(f, source, diff, masker, cfg.model(), meter);
            Verdict v = t.verdict();
            boolean criticalIssue = v.gravite() == Verdict.Gravite.CRITIQUE && v.decision() != Verdict.Decision.FAUX_POSITIF;
            boolean dismissedSerious = v.decision() == Verdict.Decision.FAUX_POSITIF && isHighSeverity(f);
            if ((criticalIssue || dismissedSerious) && !cfg.strongModel().equals(cfg.model())) {
                t = triageOnce(f, source, diff, masker, cfg.strongModel(), meter); // second avis
            }
            return t;
        } catch (Verdict.InvalidVerdictException e) {
            log.warn("alerte {}:{} non triée : {}", f.path(), f.line(), e.getMessage());
            return null; // non triée : le check reste en échec plutôt que de passer en silence
        }
    }

    private Triage triageOnce(Finding f, Path source, PullRequestDiff diff, SecretMasker masker, String model,
            UsageMeter meter) {
        AgentTools tools = new AgentTools(source, diff, masker, cfg.maxToolCalls());
        return agent.triage(f, excerpt(source, f, masker), tools, model, meter, masker);
    }

    /** Lignes signalées par Gitleaks : tout jeton y sera masqué avant envoi au modèle. */
    static Map<String, Set<Integer>> secretLines(List<Finding> findings) {
        Map<String, Set<Integer>> lines = new HashMap<>();
        for (Finding f : findings) {
            if (f.tool().equals("gitleaks") && f.line() != null) {
                lines.computeIfAbsent(f.path(), k -> new HashSet<>()).add(f.line());
            }
        }
        return lines;
    }

    /** Lignes numérotées autour de l'alerte (fichier masqué en entier), la ligne signalée marquée de «>». */
    public static String excerpt(Path source, Finding f, SecretMasker masker) {
        if (SafeFiles.isSensitive(f.path())) {
            return "(fichier sensible : contenu non transmis)";
        }
        var file = SafeFiles.resolve(source, f.path());
        if (file.isEmpty()) {
            return "(fichier indisponible)";
        }
        var read = SafeFiles.readLines(file.get());
        if (read.isEmpty()) {
            return "(fichier trop gros, binaire ou illisible)";
        }
        List<String> lines = masker.maskFile(f.path(), read.get());
        int center = f.line() == null ? 1 : f.line();
        int start = Math.max(1, center - EXCERPT_CONTEXT);
        int end = Math.min(lines.size(), f.line() == null ? 40 : center + EXCERPT_CONTEXT);
        StringBuilder sb = new StringBuilder();
        for (int n = start; n <= end; n++) {
            sb.append(f.line() != null && n == f.line() ? "> " : "  ").append(n).append(": ").append(lines.get(n - 1))
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
