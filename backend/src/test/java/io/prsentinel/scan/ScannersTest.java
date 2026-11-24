package io.prsentinel.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ScannersTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Duration T = Duration.ofSeconds(5);
    static final long MAX = 50L * 1024 * 1024;

    @TempDir
    Path tmp;

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = ScannersTest.class.getResourceAsStream("/fixtures/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    // --- rapports réels ----------------------------------------------------------------------

    @Test
    void parseSemgrepFixture() throws Exception {
        List<Finding> f = new SemgrepScanner("semgrep", "p/java", T, MAX).parse(fixture("semgrep.json"), tmp);
        assertThat(f).containsExactly(new Finding("semgrep", "subprocess-shell-true", "ERROR", "app.py", 3,
                "subprocess appelé avec shell=True"));
    }

    @Test
    void parseGitleaksFixtureNeverKeepsSecret() throws Exception {
        List<Finding> f = new GitleaksScanner("gitleaks", T, MAX).parse(fixture("gitleaks.json"), tmp);
        assertThat(f).extracting(Finding::ruleId).containsExactlyInAnyOrder("github-pat", "aws-access-token");
        assertThat(f).allMatch(x -> x.severity().equals("SECRET") && x.path().equals("config.env") && x.line() != null);
        JsonNode leaky = MAPPER.readTree("[{\"RuleID\":\"r\",\"File\":\"a\",\"StartLine\":1,"
                + "\"Secret\":\"hunter2\",\"Match\":\"pw=hunter2\"}]");
        assertThat(new GitleaksScanner("gitleaks", T, MAX).parse(leaky, tmp).toString()).doesNotContain("hunter2");
        assertThat(new GitleaksScanner("gitleaks", T, MAX).parse(MAPPER.readTree("null"), tmp)).isEmpty();
    }

    @Test
    void parseTrivyFixture() throws Exception {
        List<Finding> f = new TrivyScanner("trivy", T, MAX, false).parse(fixture("trivy.json"), tmp);
        assertThat(f.stream().filter(x -> x.path().equals("requirements.txt"))).hasSize(3)
                .allMatch(x -> x.ruleId().startsWith("CVE-") || x.ruleId().startsWith("GHSA-"));
        assertThat(f.stream().filter(x -> x.path().equals("Dockerfile"))).hasSize(2);
        JsonNode passed = MAPPER.readTree("{\"Results\":[{\"Target\":\"x\",\"Misconfigurations\":["
                + "{\"ID\":\"A\",\"Status\":\"PASS\"},{\"ID\":\"B\",\"Status\":\"FAIL\",\"Severity\":\"low\"}]}]}");
        assertThat(new TrivyScanner("trivy", T, MAX, false).parse(passed, tmp))
                .containsExactly(new Finding("trivy", "B", "LOW", "x", null, ""));
    }

    static Stream<Arguments> badShapes() {
        return Stream.of(Arguments.of("semgrep", "[]"), Arguments.of("semgrep", "{\"results\":\"x\"}"),
                Arguments.of("gitleaks", "{\"not\":\"a list\"}"), Arguments.of("trivy", "[]"),
                Arguments.of("trivy", "{\"Results\":\"x\"}"));
    }

    @ParameterizedTest
    @MethodSource("badShapes")
    void unexpectedReportShapes(String tool, String json) {
        CommandScanner scanner = switch (tool) {
            case "semgrep" -> new SemgrepScanner("semgrep", "p/java", T, MAX);
            case "gitleaks" -> new GitleaksScanner("gitleaks", T, MAX);
            default -> new TrivyScanner("trivy", T, MAX, false);
        };
        assertThatThrownBy(() -> scanner.parse(MAPPER.readTree(json), tmp)).isInstanceOf(ScannerException.class);
    }

    @Test
    void relativePaths() {
        assertThat(CommandScanner.relativePath(tmp.resolve("a/b.py").toString(), tmp)).isEqualTo("a/b.py");
        assertThat(CommandScanner.relativePath("/etc/passwd", tmp)).isEqualTo("passwd");
        assertThat(CommandScanner.relativePath("./x/y", tmp)).isEqualTo("x/y");
    }

    // --- exécution (faux binaires) -----------------------------------------------------------

    Path fakeBinary(String mode, String fixtureName) throws IOException {
        Path fixture = tmp.resolve("fixture-" + fixtureName);
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + fixtureName)) {
            Files.copy(in, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String script = """
                #!/bin/sh
                mode=%s
                [ "$mode" = sleep ] && sleep 60
                [ "$mode" = fail ] && { echo kaboom >&2; exit 2; }
                if [ "$mode" = env ]; then env | grep -q '^SENTINEL_' && exit 3; fi
                out=""
                while [ $# -gt 0 ]; do
                  case "$1" in --output|--report-path) out="$2"; shift;; esac
                  shift
                done
                [ "$mode" = garbage ] && { echo '{not json' > "$out"; exit 0; }
                [ "$mode" = noreport ] && exit 0
                cp '%s' "$out"
                """.formatted(mode, fixture);
        Path bin = tmp.resolve("fake-" + mode + "-" + fixtureName + ".sh");
        Files.writeString(bin, script);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"));
        return bin;
    }

    static Stream<Arguments> scanners() {
        Function<String, Scanner> semgrep = b -> new SemgrepScanner(b, "rules.yml", T, MAX);
        Function<String, Scanner> gitleaks = b -> new GitleaksScanner(b, T, MAX);
        Function<String, Scanner> trivy = b -> new TrivyScanner(b, Duration.ofSeconds(2), MAX, false);
        return Stream.of(Arguments.of(semgrep, "semgrep.json", 1), Arguments.of(gitleaks, "gitleaks.json", 2),
                Arguments.of(trivy, "trivy.json", 5));
    }

    @ParameterizedTest
    @MethodSource("scanners")
    void scannerSuccessAndFailuresAreReportedNotThrown(Function<String, Scanner> make, String fixture, int count)
            throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        assertThat(make.apply(fakeBinary("ok", fixture).toString()).scan(tmp, work).findings()).hasSize(count);
        assertThat(make.apply(fakeBinary("fail", fixture).toString()).scan(tmp, work).error())
                .isEqualTo("code de sortie 2");
        assertThat(make.apply(fakeBinary("noreport", fixture).toString()).scan(tmp, Files.createTempDirectory(tmp, "w"))
                .error()).isEqualTo("rapport absent");
        assertThat(make.apply(fakeBinary("garbage", fixture).toString()).scan(tmp, work).error())
                .isEqualTo("rapport JSON illisible");
    }

    @Test
    void timeoutAndMissingBinary() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        assertThat(new GitleaksScanner(fakeBinary("sleep", "gitleaks.json").toString(), Duration.ofSeconds(1), MAX)
                .scan(tmp, work).error()).isEqualTo("délai dépassé (1s)");
        assertThat(new GitleaksScanner(tmp.resolve("nope").toString(), T, MAX).scan(tmp, work).error())
                .startsWith("exécutable introuvable");
    }

    @Test
    void oversizedReportRejected() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        var scanner = new GitleaksScanner(fakeBinary("ok", "gitleaks.json").toString(), T, 10);
        assertThat(scanner.scan(tmp, work).error()).isEqualTo("rapport trop volumineux");
    }

    @Test
    void hardenedCommandLines() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        List<String> sg = new SemgrepScanner("semgrep", "p/java,p/owasp-top-ten", T, MAX).command(work.resolve("r"), work);
        assertThat(sg).contains("--disable-nosem", "--metrics=off").endsWith(".");
        assertThat(sg.stream().filter("--config"::equals)).hasSize(2);
        List<String> gl = new GitleaksScanner("gitleaks", T, MAX).command(work.resolve("r"), work);
        assertThat(gl).contains("--redact", "--ignore-gitleaks-allow");
        assertThat(Files.readString(Path.of(gl.get(gl.indexOf("--config") + 1)))).contains("useDefault = true");
        List<String> tv = new TrivyScanner("trivy", T, MAX, true).command(work.resolve("r"), work);
        assertThat(tv.get(tv.indexOf("--config") + 1)).isEmpty();
        assertThat(tv.get(tv.indexOf("--ignorefile") + 1)).isEmpty();
        assertThat(tv).contains("--offline-scan", "--skip-db-update");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PR_SENTINEL_REAL_SCANNERS", matches = "1")
    void realGitleaksIgnoresInlineAllowAndRepoConfig() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("c.env"), "k = \"AKIAIOSFODNN7EXAMPLQ\" # gitleaks:allow\n");
        Files.writeString(src.resolve(".gitleaks.toml"), "[allowlist]\npaths = ['.*']\n");
        var result = new GitleaksScanner("gitleaks", Duration.ofSeconds(60), MAX).scan(src,
                Files.createDirectories(tmp.resolve("work")));
        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("aws-access-token");
        assertThat(result.toString()).doesNotContain("AKIA");
    }
}
