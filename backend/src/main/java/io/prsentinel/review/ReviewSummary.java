package io.prsentinel.review;

import static io.prsentinel.review.MarkdownSafety.code;
import static io.prsentinel.review.MarkdownSafety.escape;

import io.prsentinel.agent.Verdict.Decision;
import io.prsentinel.scan.Finding;
import io.prsentinel.scan.ScanResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Corps de la revue GitHub et texte du check run. */
final class ReviewSummary {

    private ReviewSummary() {}

    static long count(List<AgentReviewPublisher.Item> items, Decision d) {
        return items.stream().filter(i -> i.triaged() && i.verdict().decision() == d).count();
    }

    static String render(String headSha, List<ScanResult> results, List<AgentReviewPublisher.Item> items,
            BigDecimal cost, boolean blocking) {
        long real = count(items, Decision.VRAI_PROBLEME);
        long check = count(items, Decision.A_VERIFIER);
        long fp = count(items, Decision.FAUX_POSITIF);
        long untriaged = items.stream().filter(i -> !i.triaged()).count();
        List<String> lines = new ArrayList<>();
        lines.add("## 🛡️ PR Sentinel");
        lines.add("");
        lines.add((blocking ? "⛔ **Faille critique détectée** — la fusion est bloquée par le check."
                : real > 0 ? "⚠️ Des problèmes de sécurité sont à corriger." : "✅ Aucun problème de sécurité confirmé.")
                + " Commit `" + headSha.substring(0, 12) + "`.");
        lines.add("");
        lines.add("| Vrais problèmes | À vérifier | Faux positifs écartés | Non triées |");
        lines.add("|---|---|---|---|");
        lines.add("| " + real + " | " + check + " | " + fp + " | " + untriaged + " |");
        for (ScanResult r : results) {
            if (!r.isOk()) {
                lines.add("");
                lines.add("> ⚠️ " + r.tool() + " a échoué : " + escape(r.error(), 120));
            }
        }
        List<AgentReviewPublisher.Item> noLine = items.stream().filter(i -> i.triaged()
                && i.verdict().decision() != Decision.FAUX_POSITIF && i.finding().finding().line() == null).toList();
        if (!noLine.isEmpty()) {
            lines.add("");
            lines.add("### Hors lignes de code");
            for (var i : noLine) {
                lines.add("- **" + i.verdict().gravite() + "** " + code(i.finding().finding().ruleId(), 120) + " · "
                        + code(i.finding().finding().path()) + " — " + escape(i.verdict().explication()));
            }
        }
        if (fp > 0) {
            lines.add("");
            lines.add("<details><summary>Faux positifs écartés (" + fp + ")</summary>");
            lines.add("");
            for (var i : items) {
                if (i.triaged() && i.verdict().decision() == Decision.FAUX_POSITIF) {
                    Finding f = i.finding().finding();
                    lines.add("- " + code(f.ruleId(), 120) + " · " + code(f.path() + (f.line() != null ? ":" + f.line() : ""))
                            + " — " + escape(i.verdict().explication()));
                }
            }
            lines.add("");
            lines.add("</details>");
        }
        if (untriaged > 0) {
            lines.add("");
            lines.add("<details><summary>Alertes non triées (" + untriaged + ", plafond par PR ou budget atteint)</summary>");
            lines.add("");
            for (var i : items) {
                if (!i.triaged()) {
                    Finding f = i.finding().finding();
                    lines.add("- " + escape(f.severity(), 20) + " " + code(f.ruleId(), 120) + " · "
                            + code(f.path() + (f.line() != null ? ":" + f.line() : "")));
                }
            }
            lines.add("");
            lines.add("</details>");
        }
        lines.add("");
        lines.add("<sub>Alertes des lignes modifiées seulement · secrets masqués avant envoi au modèle · coût "
                + String.format(Locale.ROOT, "%.4f", cost) + " $</sub>");
        String body = String.join("\n", lines);
        return body.length() > RawReportRenderer.MAX_COMMENT_CHARS
                ? body.substring(0, RawReportRenderer.MAX_COMMENT_CHARS - 20) + "\n\n_(tronqué)_" : body;
    }

    static String checkTitle(List<AgentReviewPublisher.Item> items, boolean blocking) {
        long real = count(items, Decision.VRAI_PROBLEME);
        if (blocking) {
            return "Faille critique détectée";
        }
        return real == 0 ? "Aucun problème de sécurité confirmé" : real + " problème(s) de sécurité à corriger";
    }

    static String checkSummary(List<AgentReviewPublisher.Item> items) {
        return count(items, Decision.VRAI_PROBLEME) + " vrai(s) problème(s), " + count(items, Decision.A_VERIFIER)
                + " à vérifier, " + count(items, Decision.FAUX_POSITIF) + " faux positif(s) écarté(s), "
                + items.stream().filter(i -> !i.triaged()).count() + " non triée(s).";
    }
}
