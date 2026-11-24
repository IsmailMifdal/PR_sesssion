package io.prsentinel.diff;

import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.scan.Finding;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffTest {

    static final String PATCH = """
            @@ -1,4 +1,5 @@
             package demo;
            -import a;
            +import b;
            +import c;
             class X {
            @@ -10,3 +11,4 @@ class X {
               void f() {
            +    run(cmd);
               }
            \\ No newline at end of file""";

    @Test
    void addedLinesFollowHunkHeaders() {
        assertThat(UnifiedDiffParser.addedLines(PATCH)).containsExactly(2, 3, 12);
        assertThat(UnifiedDiffParser.addedLines(null)).isEmpty();
        assertThat(UnifiedDiffParser.addedLines("@@ -0,0 +1,2 @@\n+a\n+b")).containsExactly(1, 2);
    }

    @Test
    void filterKeepsOnlyFindingsOnChangedLines() {
        PullRequestDiff diff = new PullRequestDiff(Map.of("A.java", Set.of(2, 3, 12), "pom.xml", Set.of(5)),
                Map.of("A.java", PATCH));
        Finding onChanged = new Finding("semgrep", "r", "ERROR", "A.java", 12, "m");
        Finding onUnchanged = new Finding("semgrep", "r", "ERROR", "A.java", 20, "m");
        Finding otherFile = new Finding("semgrep", "r", "ERROR", "B.java", 2, "m");
        Finding dependency = new Finding("trivy", "CVE-1", "HIGH", "pom.xml", null, "m");
        Finding untouchedDependency = new Finding("trivy", "CVE-2", "HIGH", "package.json", null, "m");
        assertThat(FindingFilter.keepChanged(List.of(onChanged, onUnchanged, otherFile, dependency,
                untouchedDependency), diff)).containsExactly(onChanged, dependency);
        assertThat(diff.patch("A.java")).contains(PATCH);
    }
}
