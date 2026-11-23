package io.prsentinel.review;

import io.prsentinel.github.GitHubAppClient;

/** Publication sans IA : un commentaire unique listant les alertes brutes, mis à jour à chaque push. */
public class RawCommentPublisher implements ReviewPipeline.Publisher {

    private final GitHubAppClient github;
    private final String note;

    public RawCommentPublisher(GitHubAppClient github, String note) {
        this.github = github;
        this.note = note;
    }

    @Override
    public ReviewPipeline.PublishOutcome publish(ReviewPipeline.ReviewContext ctx) {
        String body = RawReportRenderer.render(ctx.results(), ctx.job().headSha(), note);
        github.upsertReportComment(ctx.token(), ctx.job().repoFullName(), ctx.job().prNumber(), body);
        return ReviewPipeline.PublishOutcome.NONE;
    }
}
