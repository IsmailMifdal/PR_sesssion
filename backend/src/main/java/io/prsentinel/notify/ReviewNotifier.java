package io.prsentinel.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alerte Slack immédiate après une revue publiée : faille critique (check bloquant) ou tout vrai problème, selon
 * le réglage du compte. Ne transmet ni code ni explication : dépôt, PR, décompte par gravité, règle et
 * emplacement de l'alerte la plus grave, liens vers la PR et la console. Un échec n'affecte jamais la revue.
 */
public class ReviewNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReviewNotifier.class);

    /** Pas de notification (rôle sans clé de chiffrement, tests). */
    public static final ReviewNotifier NONE = new ReviewNotifier(null, null, null, null, null) {
        @Override
        public void published(long reviewId) {}
    };

    private final NotificationStore store;
    private final SecretBox box;
    private final SlackClient slack;
    private final String gitUrl;
    private final String frontUrl;

    public ReviewNotifier(NotificationStore store, SecretBox box, SlackClient slack, String gitUrl, String frontUrl) {
        this.store = store;
        this.box = box;
        this.slack = slack;
        this.gitUrl = gitUrl == null ? null : gitUrl.replaceAll("/+$", "");
        this.frontUrl = frontUrl == null || frontUrl.isBlank() ? null : frontUrl.replaceAll("/+$", "");
    }

    public void published(long reviewId) {
        try {
            var facts = store.review(reviewId).orElse(null);
            if (facts == null || box == null) {
                return;
            }
            var settings = store.get(facts.githubInstallationId());
            if (settings.slackSealed() == null || !shouldNotify(settings.slackLevel(), facts)) {
                return;
            }
            slack.post(box.decrypt(settings.slackSealed()), message(facts));
            log.info("alerte Slack envoyée pour {}#{}", facts.repo(), facts.pr());
        } catch (RuntimeException e) {
            log.warn("alerte Slack non envoyée pour la revue {} : {}", reviewId, e.getMessage());
        }
    }

    static boolean shouldNotify(String level, NotificationStore.ReviewFacts f) {
        return NotificationStore.VRAI_PROBLEME.equals(level) ? f.issues() > 0 : f.blocking() && f.critical() > 0;
    }

    String message(NotificationStore.ReviewFacts f) {
        String repo = SlackClient.escape(f.repo());
        StringBuilder sb = new StringBuilder();
        sb.append(f.blocking() ? ":no_entry: *Faille critique* sur " : ":warning: *Problème de sécurité* sur ");
        sb.append(gitUrl == null ? "*" + repo + "#" + f.pr() + "*"
                : "<" + gitUrl + "/" + repo + "/pull/" + f.pr() + "|" + repo + "#" + f.pr() + ">");
        sb.append("\n").append(counts(f));
        if (f.topRule() != null) {
            sb.append("\nLa plus grave : `").append(SlackClient.escape(f.topRule())).append("` dans `")
                    .append(SlackClient.escape(f.topLocation())).append("`");
        }
        if (f.blocking()) {
            sb.append("\nLe check PR Sentinel bloque la fusion.");
        }
        if (frontUrl != null) {
            sb.append("\n<").append(frontUrl).append("/console/revues/").append(f.reviewId()).append("|Voir la revue>");
        }
        return sb.toString();
    }

    private static String counts(NotificationStore.ReviewFacts f) {
        StringBuilder sb = new StringBuilder();
        append(sb, f.critical(), "critique");
        append(sb, f.high(), "haute");
        append(sb, f.medium(), "moyenne");
        append(sb, f.low(), "basse");
        return sb.isEmpty() ? "Aucun vrai problème." : sb.toString();
    }

    private static void append(StringBuilder sb, int n, String label) {
        if (n > 0) {
            sb.append(sb.isEmpty() ? "" : " · ").append(n).append(' ').append(label).append(n > 1 ? "s" : "");
        }
    }
}
