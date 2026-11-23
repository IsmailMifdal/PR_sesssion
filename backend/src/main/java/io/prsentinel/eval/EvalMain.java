package io.prsentinel.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.prsentinel.agent.Triage;
import io.prsentinel.agent.TriageAgent;
import io.prsentinel.agent.TriageSession;
import io.prsentinel.agent.Verdict.Decision;
import io.prsentinel.config.SentinelProperties;
import io.prsentinel.diff.FindingFilter;
import io.prsentinel.diff.PullRequestDiff;
import io.prsentinel.diff.UnifiedDiffParser;
import io.prsentinel.review.RawReportRenderer;
import io.prsentinel.scan.Finding;
import io.prsentinel.scan.GitCheckout;
import io.prsentinel.scan.GitleaksScanner;
import io.prsentinel.scan.ScanResult;
import io.prsentinel.scan.Scanner;
import io.prsentinel.scan.SemgrepScanner;
import io.prsentinel.scan.TrivyScanner;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Banc d'évaluation : rejoue les PR de {@code eval/cases} (état avant/après), lance les vrais scanners, le
 * filtre du diff et l'agent, puis calcule les métriques du plan. Sans {@code OPENAI_API_KEY}, mode
 * « scanners seuls » : il mesure le bruit de référence que l'agent doit réduire.
 *
 * <pre>mvn -q compile exec:java -Dexec.args="--cases ../eval/cases --out ../eval/report.json"</pre>
 */
public final class EvalMain {

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record CaseResult(String id, String titre, String attendu, int alertesScanners, int alertesLignesModifiees,
            int vraisProblemes, int aVerifier, int fauxPositifs, int nonTriees, boolean detecte, double secondes,
            BigDecimal coutUsd, List<Map<String, Object>> alertes) {}

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> opts = parseArgs(args);
        Path cases = Path.of(opts.getOrDefault("cases", "../eval/cases"));
        Path out = Path.of(opts.getOrDefault("out", "../eval/report.json"));
        int minDetection = Integer.parseInt(opts.getOrDefault("min-detection", "11"));
        String apiKey = System.getenv("OPENAI_API_KEY");
        SentinelProperties.Agent cfg = agentConfig(opts);
        TriageSession session = apiKey == null || apiKey.isBlank() ? null
                : new TriageSession(cfg, new TriageAgent(openAi(apiKey, cfg.model())));
        List<Scanner> scanners = scanners(opts);

        List<CaseResult> results = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(cases)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                CaseResult r = runCase(dir, scanners, session, cfg);
                results.add(r);
                System.out.printf("%-22s attendu=%-13s alertes=%d/%d vrais=%d àvérifier=%d fp=%d %s%n", r.id(),
                        r.attendu(), r.alertesLignesModifiees(), r.alertesScanners(), r.vraisProblemes(),
                        r.aVerifier(), r.fauxPositifs(), r.detecte() ? "✔" : "·");
            }
        }
        Map<String, Object> report = report(results, session != null ? "agent" : "scanners-seuls", cfg);
        Files.createDirectories(out.toAbsolutePath().getParent());
        MAPPER.writeValue(out.toFile(), report);
        System.out.println(MAPPER.writeValueAsString(report.get("metriques")));

        if (session != null) {
            int detected = (int) results.stream().filter(r -> r.attendu().equals("VRAI_PROBLEME") && r.detecte()).count();
            if (detected < minDetection) {
                System.err.println("Détection " + detected + " < " + minDetection + " : échec.");
                System.exit(1);
            }
        }
    }

    static CaseResult runCase(Path dir, List<Scanner> scanners, TriageSession session, SentinelProperties.Agent cfg)
            throws Exception {
        JsonNode meta = MAPPER.readTree(dir.resolve("case.json").toFile());
        Path work = Files.createTempDirectory("pr-sentinel-eval-");
        try {
            Path repo = materialize(dir, meta, work.resolve("repo"));
            PullRequestDiff diff = gitDiff(repo);
            GitCheckout.stripScannerOverrides(repo);
            Instant start = Instant.now();
            Path reports = Files.createDirectory(work.resolve("reports"));
            List<ScanResult> raw = scanners.stream().map(s -> s.scan(repo, reports)).toList();
            for (ScanResult r : raw) {
                if (!r.isOk()) {
                    System.err.println(dir.getFileName() + " : " + r.tool() + " a échoué : " + r.error());
                }
            }
            List<Finding> all = raw.stream().flatMap(r -> r.findings().stream()).toList();
            List<Finding> kept = FindingFilter.keepChanged(all, diff).stream()
                    .sorted(RawReportRenderer.bySeverity()).toList();

            List<Triage> triages = new ArrayList<>();
            BigDecimal cost = BigDecimal.ZERO;
            if (session != null && !kept.isEmpty()) {
                TriageSession.Result res = session.run(kept, repo, diff, new BigDecimal("1000"),
                        new io.prsentinel.agent.UsageMeter(cfg));
                triages = res.triages();
                cost = res.costUsd();
            }
            double seconds = Duration.between(start, Instant.now()).toMillis() / 1000.0;
            int real = count(triages, Decision.VRAI_PROBLEME);
            int check = count(triages, Decision.A_VERIFIER);
            int fp = count(triages, Decision.FAUX_POSITIF);
            int untriaged = (int) triages.stream().filter(t -> t == null).count()
                    + (session == null ? kept.size() : 0);
            boolean detected = session == null ? !kept.isEmpty() : real > 0;
            List<Map<String, Object>> alertes = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                Finding f = kept.get(i);
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("outil", f.tool());
                a.put("regle", f.ruleId());
                a.put("emplacement", f.path() + (f.line() != null ? ":" + f.line() : ""));
                Triage t = i < triages.size() ? triages.get(i) : null;
                if (t != null) {
                    a.put("decision", t.verdict().decision());
                    a.put("gravite", t.verdict().gravite());
                    a.put("confiance", t.verdict().confiance());
                    a.put("explication", t.verdict().explication());
                    a.put("modele", t.model());
                }
                alertes.add(a);
            }
            return new CaseResult(meta.path("id").asText(), meta.path("titre").asText(), meta.path("attendu").asText(),
                    all.size(), kept.size(), real, check, fp, untriaged, detected, seconds, cost, alertes);
        } finally {
            try (Stream<Path> walk = Files.walk(work)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static int count(List<Triage> triages, Decision d) {
        return (int) triages.stream().filter(t -> t != null && t.verdict().decision() == d).count();
    }

    /** Dépôt git local : commit « base », puis commit « PR » (base + fichiers de la PR). */
    static Path materialize(Path dir, JsonNode meta, Path repo) throws Exception {
        Files.createDirectories(repo);
        git(repo, "init", "--quiet", "-b", "main");
        copyTree(dir.resolve("base"), repo);
        git(repo, "add", "-A");
        git(repo, "commit", "--quiet", "--allow-empty", "-m", "base");
        copyTree(dir.resolve("pr"), repo);
        JsonNode subs = meta.path("substitutions");
        if (subs.isObject()) { // fausses clés reconstituées à l'exécution (jamais en clair dans le dépôt)
            try (Stream<Path> files = Files.walk(repo)) {
                for (Path f : files.filter(Files::isRegularFile).filter(p -> !p.toString().contains("/.git/")).toList()) {
                    String text = Files.readString(f);
                    String replaced = text;
                    for (var e : subs.properties()) {
                        StringBuilder value = new StringBuilder();
                        e.getValue().forEach(part -> value.append(part.asText()));
                        replaced = replaced.replace(e.getKey(), value);
                    }
                    if (!replaced.equals(text)) {
                        Files.writeString(f, replaced);
                    }
                }
            }
        }
        git(repo, "add", "-A");
        git(repo, "commit", "--quiet", "-m", "pr");
        return repo;
    }

    static PullRequestDiff gitDiff(Path repo) throws Exception {
        Map<String, Set<Integer>> lines = new LinkedHashMap<>();
        Map<String, String> patches = new LinkedHashMap<>();
        for (String file : git(repo, "diff", "--name-only", "--diff-filter=d", "HEAD~1", "HEAD").lines().toList()) {
            String patch = git(repo, "diff", "HEAD~1", "HEAD", "--", file);
            lines.put(file, UnifiedDiffParser.addedLines(patch));
            patches.put(file, patch.substring(Math.max(0, patch.indexOf("@@"))));
        }
        return new PullRequestDiff(lines, patches);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "user.name=eval", "-c", "user.email=eval@example.test",
                "-c", "commit.gpgsign=false"));
        argv.addAll(List.of(args));
        Path outFile = Files.createTempFile("git-out", ".txt");
        try {
            Process p = new ProcessBuilder(argv).directory(cwd.toFile()).redirectOutput(outFile.toFile())
                    .redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor() != 0) {
                throw new IOException("git " + String.join(" ", args) + " a échoué");
            }
            return Files.readString(outFile);
        } finally {
            Files.deleteIfExists(outFile);
        }
    }

    static Map<String, Object> report(List<CaseResult> results, String mode, SentinelProperties.Agent cfg) {
        List<CaseResult> vrais = results.stream().filter(r -> r.attendu().equals("VRAI_PROBLEME")).toList();
        List<CaseResult> pieges = results.stream().filter(r -> r.attendu().equals("FAUX_POSITIF")).toList();
        int detected = (int) vrais.stream().filter(CaseResult::detecte).count();
        int scannerFp = pieges.stream().mapToInt(CaseResult::alertesLignesModifiees).sum();
        int fpDiscarded = pieges.stream().mapToInt(CaseResult::fauxPositifs).sum();
        int publishedOnTraps = pieges.stream().mapToInt(r -> r.vraisProblemes() + r.aVerifier()
                + (mode.equals("agent") ? 0 : r.alertesLignesModifiees())).sum();
        int publishedTotal = results.stream().mapToInt(r -> r.vraisProblemes() + r.aVerifier()
                + (mode.equals("agent") ? 0 : r.alertesLignesModifiees())).sum();
        double avgSeconds = results.stream().mapToDouble(CaseResult::secondes).average().orElse(0);
        BigDecimal avgCost = results.isEmpty() ? BigDecimal.ZERO : results.stream().map(CaseResult::coutUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(results.size()), 6, RoundingMode.HALF_UP);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("detection", detected + "/" + vrais.size());
        metrics.put("bruitEnMoinsPct", scannerFp == 0 ? null : round(100.0 * fpDiscarded / scannerFp));
        metrics.put("faussesAlertesRestantesPct", publishedTotal == 0 ? null : round(100.0 * publishedOnTraps / publishedTotal));
        metrics.put("tempsMoyenScanEtTriageS", round(avgSeconds));
        metrics.put("coutMoyenParPrUsd", avgCost);
        metrics.put("alertesScannersSurPieges", scannerFp);

        Map<String, Object> goals = new LinkedHashMap<>();
        goals.put("detection", ">= 11/12");
        goals.put("bruitEnMoinsPct", ">= 70");
        goals.put("faussesAlertesRestantesPct", "<= 15");
        goals.put("tempsParPrS", "< 90 (webhook → revue publiée ; ici : scan + triage seulement)");
        goals.put("coutParPrUsd", "< 0.01");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", Instant.now().toString());
        report.put("mode", mode);
        report.put("modele", mode.equals("agent") ? cfg.model() + " (fort : " + cfg.strongModel() + ")" : null);
        report.put("metriques", metrics);
        report.put("objectifs", goals);
        report.put("cas", results);
        return report;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static List<Scanner> scanners(Map<String, String> opts) {
        Duration t = Duration.ofSeconds(Long.parseLong(opts.getOrDefault("scanner-timeout", "300")));
        long max = 50L * 1024 * 1024;
        return List.of(
                new SemgrepScanner(opts.getOrDefault("semgrep-bin", "semgrep"),
                        opts.getOrDefault("semgrep-config", "p/java,p/typescript,p/owasp-top-ten"), t, max),
                new GitleaksScanner(opts.getOrDefault("gitleaks-bin", "gitleaks"), t, max),
                new TrivyScanner(opts.getOrDefault("trivy-bin", "trivy"), t, max,
                        Boolean.parseBoolean(opts.getOrDefault("trivy-offline-db", "false"))));
    }

    static SentinelProperties.Agent agentConfig(Map<String, String> opts) {
        return new SentinelProperties.Agent(true, opts.getOrDefault("model", "gpt-4o-mini"),
                opts.getOrDefault("strong-model", "gpt-4o"), 30, 5, new BigDecimal("1000"), new BigDecimal("0.15"),
                new BigDecimal("0.60"), new BigDecimal("2.50"), new BigDecimal("10.00"), Duration.ofMinutes(5));
    }

    static ChatModel openAi(String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.0).build()).build();
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("option attendue : " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        return opts;
    }

    private EvalMain() {}

}
