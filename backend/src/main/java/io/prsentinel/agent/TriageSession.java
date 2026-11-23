package io.prsentinel.agent;

import io.prsentinel.config.SentinelProperties;
import io.prsentinel.diff.PullRequestDiff;
import io.prsentinel.scan.Finding;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Triage des alertes d'une PR, dans l'ordre donné (les plus graves d'abord) : plafond d'alertes, budget,
 * masquage des secrets, second avis du modèle fort pour les alertes critiques. Partagé par la publication
 * GitHub et par le banc d'évaluation.
 */
public class TriageSession {

    static final int EXCERPT_CONTEXT = 8;

    /** {@code triages.get(i)} est {@code null} si l'alerte i n'a pas été triée (plafond ou budget). */
    public record Result(List<Triage> triages, long tokensIn, long tokensOut, BigDecimal costUsd) {}

    private final SentinelProperties.Agent cfg;
    private final TriageAgent agent;

    public TriageSession(SentinelProperties.Agent cfg, TriageAgent agent) {
        this.cfg = cfg;
        this.agent = agent;
    }

    public Result run(List<Finding> ordered, Path source, PullRequestDiff diff, BigDecimal budgetLeft) {
        SecretMasker masker = new SecretMasker(secretLines(ordered));
        List<Triage> triages = new ArrayList<>();
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        int analysed = 0;
        for (Finding f : ordered) {
            if (analysed >= cfg.maxFindingsPerPr() || cost.compareTo(budgetLeft) >= 0) {
                triages.add(null);
                continue;
            }
            analysed++;
            Triage t = triageOnce(f, source, diff, masker, cfg.model());
            if (t.verdict().gravite() == Verdict.Gravite.CRITIQUE && t.verdict().decision() != Verdict.Decision.FAUX_POSITIF
                    && !cfg.strongModel().equals(cfg.model())) {
                in += t.tokensIn();
                out += t.tokensOut();
                cost = cost.add(Pricing.cost(cfg, t.model(), t.tokensIn(), t.tokensOut()));
                t = triageOnce(f, source, diff, masker, cfg.strongModel()); // second avis quand l'enjeu est critique
            }
            in += t.tokensIn();
            out += t.tokensOut();
            cost = cost.add(Pricing.cost(cfg, t.model(), t.tokensIn(), t.tokensOut()));
            triages.add(t);
        }
        return new Result(triages, in, out, cost);
    }

    private Triage triageOnce(Finding f, Path source, PullRequestDiff diff, SecretMasker masker, String model) {
        AgentTools tools = new AgentTools(source, diff, masker, cfg.maxToolCalls());
        return agent.triage(f, excerpt(source, f, masker), tools, model);
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

    /** Lignes numérotées autour de l'alerte (masquées), la ligne signalée marquée de «>». */
    public static String excerpt(Path source, Finding f, SecretMasker masker) {
        Path root = source.toAbsolutePath().normalize();
        Path file = root.resolve(f.path()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return "(fichier indisponible)";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int center = f.line() == null ? 1 : f.line();
            int start = Math.max(1, center - EXCERPT_CONTEXT);
            int end = Math.min(lines.size(), f.line() == null ? 40 : center + EXCERPT_CONTEXT);
            StringBuilder sb = new StringBuilder();
            for (int n = start; n <= end; n++) {
                sb.append(f.line() != null && n == f.line() ? "> " : "  ").append(n).append(": ")
                        .append(masker.maskLine(f.path(), n, lines.get(n - 1))).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "(fichier binaire ou illisible)";
        }
    }
}
