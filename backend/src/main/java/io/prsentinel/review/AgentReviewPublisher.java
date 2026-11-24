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
            return new RawCommentPublisher(github, store, props.github().checkName(),
                    "Budget IA quotidien atteint pour ce dépôt : alertes brutes, non triées.").publish(ctx);
        }

        List<ReviewStore.StoredFinding> ordered = ctx.findings().stream()
                .sorted((a, b) -> RawReportRenderer.bySeverity().compare(a.finding(), b.finding())).toList();
        TriageSession.Result session = new TriageSession(cfg, agent).run(
                ordered.stream().map(ReviewStore.StoredFinding::finding).toList(), ctx.source(), ctx.diff(),
                cfg.dailyBudgetUsdPerRepo().subtract(spent), ctx.meter());
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

        CheckDecision decision = CheckDecision.of(items, ctx.results());
        boolean blocking = !decision.success();
        List<GitHubAppClient.ReviewComment> comments = lineComments(items, ctx);
        String summary = ReviewSummary.render(ctx.job().headSha(), ctx.results(), items, ctx.meter().cost(),
                decision) + ReviewSummary.ignoredLine(ctx.ignoredByConfig());
        ctx.ensureLeaseOwner().run(); // ne jamais publier si un autre worker a repris la tâche
        github.createReview(ctx.token(), repo, ctx.job().prNumber(), ctx.job().headSha(), summary, comments);
        Publication.finishWithCheck(github, store, ctx, props.github().checkName(), decision);
        return new ReviewPipeline.PublishOutcome(ctx.meter().tokensIn(), ctx.meter().tokensOut(), ctx.meter().cost(),
                blocking);
    }

    /** Bloc de lignes [début, fin] que remplace un correctif publiable en suggestion. */
    record FixBlock(int start, int end, String code) {}

    /**
     * Le correctif n'est proposé en bloc {@code suggestion} (applicable en un clic) que si l'on sait exactement
     * quelles lignes il remplace et qu'elles sont toutes modifiées par la PR : sinon, appliquer la
     * suggestion pourrait laisser l'ancienne ligne voisine et casser le code.
     */
    static java.util.Optional<FixBlock> fixBlock(Item item, ReviewPipeline.ReviewContext ctx) {
        Finding f = item.finding().finding();
        Verdict v = item.verdict();
        if (v.decision() != Decision.VRAI_PROBLEME || f.line() == null) {
            return java.util.Optional.empty();
        }
        var code = SuggestionPolicy.accept(v.correctif());
        if (code.isEmpty()) {
            return java.util.Optional.empty();
        }
        int start;
        int end;
        if (v.correctifLigneDebut() != null) {
            start = v.correctifLigneDebut();
            end = v.correctifLigneFin();
        } else if (!code.get().contains("\n")) {
            start = f.line(); // correctif d'une ligne sans bloc précisé : il remplace la ligne signalée
            end = f.line();
        } else {
            return java.util.Optional.empty(); // plusieurs lignes sans bloc précisé : on ne devine pas
        }
        boolean nearFinding = f.line() >= start - Verdict.MAX_REPLACED_LINES && f.line() <= end + Verdict.MAX_REPLACED_LINES;
        for (int n = start; n <= end; n++) {
            if (!ctx.diff().commentable(f.path(), n)) {
                return java.util.Optional.empty();
            }
        }
        if (!nearFinding) {
            return java.util.Optional.empty();
        }
        String indent = io.prsentinel.agent.SafeFiles.resolve(ctx.source(), f.path())
                .flatMap(io.prsentinel.agent.SafeFiles::readLines)
                .filter(lines -> start <= lines.size())
                .map(lines -> leadingWhitespace(lines.get(start - 1))).orElse("");
        return java.util.Optional.of(new FixBlock(start, end, reindent(code.get(), indent)));
    }

    static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * Les modèles rognent souvent l'indentation (de la 1re ligne, ou de tout le bloc) : on la rétablit à
     * partir de la ligne d'origine, pour que la suggestion appliquée reste correctement indentée.
     */
    static String reindent(String fix, String originalIndent) {
        if (originalIndent.isEmpty()) {
            return fix;
        }
        String[] lines = fix.split("\n", -1);
        boolean allFlush = java.util.Arrays.stream(lines).filter(l -> !l.isBlank())
                .allMatch(l -> leadingWhitespace(l).isEmpty());
        for (int i = 0; i < lines.length; i++) {
            boolean firstTrimmed = i == 0 && leadingWhitespace(lines[0]).isEmpty();
            if (!lines[i].isBlank() && (allFlush || firstTrimmed)) {
                lines[i] = originalIndent + lines[i];
            }
        }
        return String.join("\n", lines);
    }

    /** Commentaires de ligne : vrais problèmes et alertes à vérifier, sur les lignes modifiées. */
    static List<GitHubAppClient.ReviewComment> lineComments(List<Item> items, ReviewPipeline.ReviewContext ctx) {
        List<GitHubAppClient.ReviewComment> comments = new ArrayList<>();
        for (Item item : items) {
            Finding f = item.finding().finding();
            if (!item.triaged() || item.verdict().decision() == Decision.FAUX_POSITIF || f.line() == null
                    || !ctx.diff().commentable(f.path(), f.line())) {
                continue;
            }
            var block = fixBlock(item, ctx);
            if (block.isPresent()) { // ancré sur le bloc remplacé, pour que la suggestion s'applique proprement
                comments.add(new GitHubAppClient.ReviewComment(f.path(), block.get().start(), block.get().end(),
                        lineComment(item, block)));
            } else {
                comments.add(new GitHubAppClient.ReviewComment(f.path(), f.line(), lineComment(item, block)));
            }
        }
        return comments;
    }

    static String lineComment(Item item, java.util.Optional<FixBlock> block) {
        Finding f = item.finding().finding();
        Verdict v = item.verdict();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(v.gravite().name()).append("**")
                .append(v.decision() == Decision.A_VERIFIER ? " · à vérifier" : "")
                .append(" · ").append(code(f.ruleId(), 120)).append(" · ").append(code(f.path() + ":" + f.line()))
                .append("\n\n").append(escape(v.explication(), Verdict.MAX_EXPLANATION_CHARS)).append("\n");
        boolean hasFix = v.decision() == Decision.VRAI_PROBLEME && v.correctif() != null && !v.correctif().isBlank();
        if (block.isPresent()) {
            sb.append("\n_Correctif proposé par l'IA (lignes ").append(block.get().start()).append(" à ")
                    .append(block.get().end()).append("), à relire avant de l'appliquer :_\n")
                    .append(MarkdownSafety.fencedSuggestion(block.get().code())).append("\n");
        } else if (hasFix && SuggestionPolicy.accept(v.correctif()).isPresent()) {
            // Correctif sûr mais bloc à remplacer incertain : montré pour inspiration, pas applicable en un clic.
            sb.append("\n_Piste de correctif (non applicable en un clic : à adapter à la main) :_\n")
                    .append(MarkdownSafety.fencedCode(v.correctif())).append("\n");
        } else if (hasFix) {
            sb.append("\n_Correctif non publié : il ne passe pas le contrôle de sécurité des suggestions._\n");
        }
        sb.append("\n<sub>").append(f.tool()).append(" · confiance ")
                .append(String.format(Locale.FRANCE, "%.2f", v.confiance())).append(" · ")
                .append(item.triage().model()).append("</sub>");
        return sb.toString();
    }
}
