package io.prsentinel.review;

import static io.prsentinel.review.MarkdownSafety.code;
import static io.prsentinel.review.MarkdownSafety.escape;

import io.prsentinel.agent.Triage;
import io.prsentinel.agent.TriageAgent;
import io.prsentinel.agent.TriageSession;
import io.prsentinel.agent.Verdict;
import io.prsentinel.agent.Verdict.Decision;
import io.prsentinel.config.SentinelProperties;
import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.scan.Finding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triage IA puis publication : revue GitHub (résumé, commentaires sur les lignes, blocs suggestion) et
 * check run en échec s'il reste une faille critique. Plafonds : alertes par PR et coût quotidien par dépôt ;
 * au-delà, alertes brutes.
 */
public class AgentReviewPublisher implements ReviewPipeline.Publisher {

    private static final Logger log = LoggerFactory.getLogger(AgentReviewPublisher.class);
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
        TriageSession.Result session = new TriageSession(cfg, agent).run(
                ordered.stream().map(ReviewStore.StoredFinding::finding).toList(), ctx.source(), ctx.diff(),
                cfg.dailyBudgetUsdPerRepo().subtract(spent));
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            ReviewStore.StoredFinding sf = ordered.get(i);
            Triage t = session.triages().get(i);
            if (t != null) {
                Verdict v = t.verdict();
                store.saveVerdict(sf.id(), v.decision().name(), v.gravite().name(), v.explication(), v.correctif(),
                        v.confiance(), t.model());
            }
            items.add(new Item(sf, t));
        }
        long in = session.tokensIn();
        long out = session.tokensOut();
        BigDecimal cost = session.costUsd();

        boolean blocking = items.stream().anyMatch(i -> i.triaged() && i.verdict().blocking());
        List<GitHubAppClient.ReviewComment> comments = lineComments(items, ctx);
        String summary = ReviewSummary.render(ctx.job().headSha(), ctx.results(), items, cost, blocking);
        github.createReview(ctx.token(), repo, ctx.job().prNumber(), ctx.job().headSha(), summary, comments);
        github.createCheckRun(ctx.token(), repo, props.github().checkName(), ctx.job().headSha(), !blocking,
                ReviewSummary.checkTitle(items, blocking), ReviewSummary.checkSummary(items));
        return new ReviewPipeline.PublishOutcome(in, out, cost, blocking);
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
