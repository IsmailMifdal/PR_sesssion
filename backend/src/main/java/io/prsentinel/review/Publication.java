package io.prsentinel.review;

import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.github.GitHubException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Étapes de publication communes : check run avec quelques essais, marquage « publiée » en base. */
final class Publication {

    private static final Logger log = LoggerFactory.getLogger(Publication.class);
    static final int CHECK_ATTEMPTS = 3;

    private Publication() {}

    /**
     * Le commentaire ou la revue est déjà visible : on marque la revue publiée (un nouvel essai ne la
     * republiera pas), puis on crée le check run en réessayant sur place les erreurs transitoires.
     */
    static void finishWithCheck(GitHubAppClient github, ReviewStore store, ReviewPipeline.ReviewContext ctx,
            String checkName, CheckDecision decision) {
        store.markPublished(ctx.reviewId());
        for (int attempt = 1; attempt <= CHECK_ATTEMPTS; attempt++) {
            try {
                github.createCheckRun(ctx.token(), ctx.job().repoFullName(), checkName, ctx.job().headSha(),
                        decision.success(), decision.title(), decision.summary());
                return;
            } catch (GitHubException e) {
                if (e.isPermanent() || attempt == CHECK_ATTEMPTS) {
                    log.error("check run non créé pour {}@{} : {}", ctx.job().repoFullName(), ctx.job().headSha(),
                            e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
