package io.prsentinel.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.scan.Finding;
import io.prsentinel.scan.ScanResult;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RawReportRendererTest {

    static final String SHA = "0123456789abcdef".repeat(2) + "01234567";

    static Finding finding(String severity, String rule, String path, Integer line, String msg) {
        return new Finding("semgrep", rule, severity, path, line, msg);
    }

    @Test
    void summaryAndSections() {
        String body = RawReportRenderer.render(List.of(
                ScanResult.ok("semgrep", List.of(finding("ERROR", "r1", "app.py", 3, "msg"))),
                ScanResult.ok("gitleaks", List.of()), ScanResult.failed("trivy", "délai dépassé (300s)")), SHA,
                "budget IA atteint");
        assertThat(body).startsWith(GitHubAppClient.MARKER).contains("`0123456789ab`")
                .contains("| semgrep | 🔎 1 alerte(s) |").contains("| gitleaks | ✅ aucune alerte |")
                .contains("| trivy | ⚠️ échec : délai dépassé \\(300s\\) |")
                .contains("| ERROR | `r1` | `app.py:3` | msg |").contains("> budget IA atteint")
                .doesNotContain("<b>gitleaks</b>");
    }

    @Test
    void sortedBySeverity() {
        String body = RawReportRenderer.render(List.of(ScanResult.ok("trivy", List.of(
                finding("LOW", "low", "a", null, ""), finding("CRITICAL", "crit", "a", null, ""),
                finding("MEDIUM", "med", "a", null, "")))), SHA, null);
        assertThat(body.indexOf("crit")).isLessThan(body.indexOf("med"));
        assertThat(body.indexOf("med")).isLessThan(body.indexOf("low"));
    }

    @Test
    void untrustedTextNeutralised() {
        String out = MarkdownSafety.escape("a|b <script>alert(1)</script> @octocat www.evil.example https://x\nnext");
        assertThat(out).doesNotContain("<script>", "@octocat", "www.evil", "https://", "\n").contains("&lt;script&gt;");
        assertThat(MarkdownSafety.escape("x".repeat(1000), 50)).hasSize(50);
        String body = RawReportRenderer.render(List.of(ScanResult.ok("gitleaks",
                List.of(finding("SECRET", "r", "evil|`</details>`@all.py", null, "")))), SHA, null);
        assertThat(body).contains("| `evil\\|'</details>'@all.py` |");
    }

    @Test
    void suggestionFenceCannotBeClosedByContent() {
        assertThat(MarkdownSafety.fencedSuggestion("a ``` b")).startsWith("````suggestion\n").endsWith("\n````");
    }

    @Test
    void truncatedUnderGitHubLimit() {
        List<Finding> many = IntStream.range(0, 2000)
                .mapToObj(i -> finding("ERROR", "rule-" + i, "a.py", i + 1, "m".repeat(250))).toList();
        String body = RawReportRenderer.render(List.of(ScanResult.ok("semgrep", many)), SHA, null);
        assertThat(body.length()).isLessThanOrEqualTo(RawReportRenderer.MAX_COMMENT_CHARS);
        assertThat(body).contains("supplémentaire(s) non affichée(s)").endsWith("</sub>");
    }
}
