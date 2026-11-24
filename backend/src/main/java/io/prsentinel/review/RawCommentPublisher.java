package io.prsentinel.review;

import io.prsentinel.github.GitHubAppClient;

/**
 * Publication sans IA : un commentaire unique listant les alertes brutes, mis à jour à chaque push, et un
 * check run en échec tant qu'il reste des alertes graves non triées.
 */
public class RawCommentPublisher implements ReviewPipeline.Publisher {

    private final GitHubAppClient github;
    private final ReviewStore store;
    private final String checkName;
    private final String note;

    public RawCommentPublisher(GitHubAppClient github, ReviewStore store, String checkName, String note) {
        this.github = github;
        this.store = store;
        this.checkName = checkName;
        this.note = note;
    }

    @Override
    public ReviewPipeline.PublishOutcome publish(ReviewPipeline.ReviewContext ctx) {
        String body = RawReportRenderer.render(ctx.results(), ctx.job().headSha(), note)
                + ReviewSummary.ignoredLine(ctx.ignoredByConfig());
        ctx.ensureLeaseOwner().run();
        github.upsertReportComment(ctx.token(), ctx.job().repoFullName(), ctx.job().prNumber(), body);
        CheckDecision decision = CheckDecision.raw(ctx.results(),
                note != null ? note : "Agent IA inactif : alertes brutes, non triées.");
        Publication.finishWithCheck(github, store, ctx, checkName, decision);
        return new ReviewPipeline.PublishOutcome(ctx.meter().tokensIn(), ctx.meter().tokensOut(), ctx.meter().cost(),
                !decision.success());
    }
}
