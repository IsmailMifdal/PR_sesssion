package io.prsentinel.review;

import static io.prsentinel.review.MarkdownSafety.code;
import static io.prsentinel.review.MarkdownSafety.escape;

import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.scan.Finding;
import io.prsentinel.scan.ScanResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Commentaire « alertes brutes » (sans IA) : utilisé quand l'agent est désactivé ou le budget atteint. */
public final class RawReportRenderer {

    public static final int MAX_COMMENT_CHARS = 65_536;
    private static final int BUDGET = MAX_COMMENT_CHARS - 2_000;
    private static final Map<String, Integer> ORDER = Map.of("CRITICAL", 0, "SECRET", 0, "HIGH", 1, "ERROR", 1,
            "MEDIUM", 2, "WARNING", 2, "LOW", 3, "INFO", 4);

    private RawReportRenderer() {}

    public static int severityRank(String severity) {
        return ORDER.getOrDefault(severity, 9);
    }

    public static Comparator<Finding> bySeverity() {
        return Comparator.comparingInt((Finding f) -> severityRank(f.severity())).thenComparing(Finding::path)
                .thenComparing(f -> f.line() == null ? 0 : f.line());
    }

    public static String render(List<ScanResult> results, String headSha, String note) {
        int total = results.stream().mapToInt(r -> r.findings().size()).sum();
        List<String> lines = new ArrayList<>(List.of(GitHubAppClient.MARKER, "## 🛡️ PR Sentinel — alertes brutes", "",
                "Commit analysé : `" + headSha.substring(0, 12) + "` · " + total
                        + " alerte(s) sur les lignes modifiées, **non triées**.",
                ""));
        if (note != null) {
            lines.add("> " + escape(note, 300));
            lines.add("");
        }
        lines.add("| Outil | Résultat |");
        lines.add("|---|---|");
        for (ScanResult r : results) {
            lines.add("| " + r.tool() + " | " + status(r) + " |");
        }
        int used = lines.stream().mapToInt(l -> l.length() + 1).sum();
        for (ScanResult r : results) {
            if (r.findings().isEmpty()) {
                continue;
            }
            List<String> section = new ArrayList<>(List.of("", "<details><summary><b>" + r.tool() + "</b> — "
                    + r.findings().size() + " alerte(s)</summary>", "", "| Sévérité | Règle | Emplacement | Message |",
                    "|---|---|---|---|"));
            used += section.stream().mapToInt(l -> l.length() + 1).sum() + 12;
            List<Finding> sorted = r.findings().stream().sorted(bySeverity()).toList();
            int shown = 0;
            for (Finding f : sorted) {
                String row = "| " + escape(f.severity(), 20) + " | " + code(f.ruleId(), 120) + " | "
                        + code(f.path() + (f.line() != null ? ":" + f.line() : "")) + " | " + escape(f.message()) + " |";
                if (used + row.length() + 1 > BUDGET) {
                    break;
                }
                section.add(row);
                used += row.length() + 1;
                shown++;
            }
            if (shown < sorted.size()) {
                section.add("");
                section.add("_… " + (sorted.size() - shown) + " alerte(s) supplémentaire(s) non affichée(s) "
                        + "(limite de taille des commentaires GitHub)._");
            }
            section.add("");
            section.add("</details>");
            lines.addAll(section);
        }
        lines.add("");
        lines.add("<sub>Les secrets détectés sont masqués : seuls la règle et l'emplacement sont indiqués."
                + " Ce commentaire est mis à jour à chaque push.</sub>");
        String body = String.join("\n", lines);
        return body.length() > MAX_COMMENT_CHARS ? body.substring(0, MAX_COMMENT_CHARS) : body;
    }

    private static String status(ScanResult r) {
        if (!r.isOk()) {
            return "⚠️ échec : " + escape(r.error(), 120);
        }
        return r.findings().isEmpty() ? "✅ aucune alerte" : "🔎 " + r.findings().size() + " alerte(s)";
    }
}
