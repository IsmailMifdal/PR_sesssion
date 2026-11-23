package io.prsentinel.review;

import static io.prsentinel.review.MarkdownSafety.code;
import static io.prsentinel.review.MarkdownSafety.escape;

import io.prsentinel.agent.AgentTools;
import io.prsentinel.agent.Pricing;
import io.prsentinel.agent.SecretMasker;
import io.prsentinel.agent.Triage;
import io.prsentinel.agent.TriageAgent;
import io.prsentinel.agent.Verdict;
import io.prsentinel.agent.Verdict.Decision;
import io.prsentinel.config.SentinelProperties;
import io.prsentinel.github.GitHubAppClient;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triage IA puis publication : revue GitHub (résumé, commentaires sur les lignes, blocs suggestion) et
 * check run en échec s'il reste une faille critique. Plafonds : alertes par PR et coût quotidien par dépôt ;
 * au-delà, alertes brutes.
 */
public class AgentReviewPublisher implements ReviewPipeline.Publisher {

    private static final Logger log = LoggerFactory.getLogger(AgentReviewPublisher.class);
    static final int EXCERPT_CONTEXT = 8;

    /** Une alerte et son verdict (absent si non triée). */
    public record Item(ReviewStore.StoredFinding finding, Triage triage) {
        boolean triaged() {
            return triage != null;
        }

        Verdict verdict() {
            return triage.verdict();
        }
    }

    private final SentinelProperties props;
    private final GitHubAppClient github;
    private final ReviewStore store;
    private final TriageAgent agent;

    public AgentReviewPublisher(SentinelProperties props, GitHubAppClient github, ReviewStore store,
            TriageAgent agent) {
        this.props = props;
        this.github = github;
        this.store = store;
        this.agent = agent;
    }

    @Override
    public ReviewPipeline.PublishOutcome publish(ReviewPipeline.ReviewContext ctx) {
        var cfg = props.agent();
        String repo = ctx.job().repoFullName();
        BigDecimal spent = store.costToday(repo);
        if (spent.compareTo(cfg.dailyBudgetUsdPerRepo()) >= 0) {
            log.info("budget IA quotidien atteint pour {} : alertes brutes", repo);
            return new RawCommentPublisher(github, "Budget IA quotidien atteint pour ce dépôt : alertes brutes, non triées.")
                    .publish(ctx);
        }

        List<ReviewStore.StoredFinding> ordered = ctx.findings().stream()
                .sorted((a, b) -> RawReportRenderer.bySeverity().compare(a.finding(), b.finding())).toList();
        SecretMasker masker = new SecretMasker(secretLines(ordered));
        List<Item> items = new ArrayList<>();
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        int analysed = 0;
        for (ReviewStore.StoredFinding sf : ordered) {
            boolean overCount = analysed >= cfg.maxFindingsPerPr();
            boolean overBudget = spent.add(cost).compareTo(cfg.dailyBudgetUsdPerRepo()) >= 0;
            if (overCount || overBudget) {
                items.add(new Item(sf, null));
                continue;
            }
            analysed++;
            Triage triage = triage(ctx, sf.finding(), masker, cfg.model());
            if (triage.verdict().gravite() == Verdict.Gravite.CRITIQUE
                    && triage.verdict().decision() != Decision.FAUX_POSITIF && !cfg.strongModel().equals(cfg.model())) {
                // Second avis du modèle fort quand l'enjeu est critique.
                in += triage.tokensIn();
                out += triage.tokensOut();
                cost = cost.add(Pricing.cost(cfg, triage.model(), triage.tokensIn(), triage.tokensOut()));
                triage = triage(ctx, sf.finding(), masker, cfg.strongModel());
            }
            in += triage.tokensIn();
            out += triage.tokensOut();
            cost = cost.add(Pricing.cost(cfg, triage.model(), triage.tokensIn(), triage.tokensOut()));
            Verdict v = triage.verdict();
            store.saveVerdict(sf.id(), v.decision().name(), v.gravite().name(), v.explication(), v.correctif(),
                    v.confiance(), triage.model());
            items.add(new Item(sf, triage));
        }

        boolean blocking = items.stream().anyMatch(i -> i.triaged() && i.verdict().blocking());
        List<GitHubAppClient.ReviewComment> comments = lineComments(items, ctx);
        String summary = ReviewSummary.render(ctx.job().headSha(), ctx.results(), items, cost, blocking);
        github.createReview(ctx.token(), repo, ctx.job().prNumber(), ctx.job().headSha(), summary, comments);
        github.createCheckRun(ctx.token(), repo, props.github().checkName(), ctx.job().headSha(), !blocking,
                ReviewSummary.checkTitle(items, blocking), ReviewSummary.checkSummary(items));
        return new ReviewPipeline.PublishOutcome(in, out, cost, blocking);
    }

    private Triage triage(ReviewPipeline.ReviewContext ctx, Finding f, SecretMasker masker, String model) {
        AgentTools tools = new AgentTools(ctx.source(), ctx.diff(), masker, props.agent().maxToolCalls());
        return agent.triage(f, excerpt(ctx.source(), f, masker), tools, model);
    }

    /** Lignes signalées par Gitleaks : tout jeton y sera masqué avant envoi au modèle. */
    static Map<String, Set<Integer>> secretLines(List<ReviewStore.StoredFinding> findings) {
        Map<String, Set<Integer>> lines = new HashMap<>();
        for (var sf : findings) {
            Finding f = sf.finding();
            if (f.tool().equals("gitleaks") && f.line() != null) {
                lines.computeIfAbsent(f.path(), k -> new HashSet<>()).add(f.line());
            }
        }
        return lines;
    }

    /** Lignes numérotées autour de l'alerte (masquées), la ligne signalée marquée de «>». */
    static String excerpt(Path source, Finding f, SecretMasker masker) {
        Path file = source.resolve(f.path()).normalize();
        if (!file.startsWith(source) || !Files.isRegularFile(file)) {
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

    /** Commentaires de ligne : vrais problèmes et alertes à vérifier, sur les lignes modifiées. */
    static List<GitHubAppClient.ReviewComment> lineComments(List<Item> items, ReviewPipeline.ReviewContext ctx) {
        List<GitHubAppClient.ReviewComment> comments = new ArrayList<>();
        for (Item item : items) {
            Finding f = item.finding().finding();
            if (!item.triaged() || item.verdict().decision() == Decision.FAUX_POSITIF || f.line() == null
                    || !ctx.diff().touchesLine(f.path(), f.line())) {
                continue;
            }
            comments.add(new GitHubAppClient.ReviewComment(f.path(), f.line(), lineComment(item)));
        }
        return comments;
    }

    static String lineComment(Item item) {
        Finding f = item.finding().finding();
        Verdict v = item.verdict();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(v.gravite().name()).append("**")
                .append(v.decision() == Decision.A_VERIFIER ? " · à vérifier" : "")
                .append(" · ").append(code(f.ruleId(), 120)).append(" · ").append(code(f.path() + ":" + f.line()))
                .append("\n\n").append(escape(v.explication(), Verdict.MAX_EXPLANATION_CHARS)).append("\n");
        if (v.decision() == Decision.VRAI_PROBLEME && v.correctif() != null && !v.correctif().isBlank()) {
            sb.append("\n").append(MarkdownSafety.fencedSuggestion(v.correctif())).append("\n");
        }
        sb.append("\n<sub>").append(f.tool()).append(" · confiance ")
                .append(String.format(Locale.FRANCE, "%.2f", v.confiance())).append(" · ")
                .append(item.triage().model()).append("</sub>");
        return sb.toString();
    }
}
