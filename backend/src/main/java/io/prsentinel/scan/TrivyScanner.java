package io.prsentinel.scan;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trivy (mode fs : dépendances vulnérables et mauvaises configurations). Ni {@code trivy.yaml} ni
 * {@code .trivyignore} de la PR ; aucune requête réseau pilotée par le contenu du dépôt.
 */
public class TrivyScanner extends CommandScanner {

    private final String binary;
    private final boolean offlineDb;

    public TrivyScanner(String binary, Duration timeout, long maxReportBytes, boolean offlineDb) {
        super(timeout, maxReportBytes);
        this.binary = binary;
        this.offlineDb = offlineDb;
    }

    @Override
    public String name() {
        return "trivy";
    }

    @Override
    Duration processTimeout() {
        return timeout.plusSeconds(30);
    }

    @Override
    List<String> command(Path report, Path workdir) {
        List<String> argv = new ArrayList<>(List.of(binary, "fs", "--config", "", "--ignorefile", "",
                "--offline-scan", "--format", "json", "--output", report.toString(), "--scanners",
                "vuln,misconfig", "--exit-code", "0", "--quiet", "--timeout", timeout.toSeconds() + "s"));
        if (offlineDb) { // base de vulnérabilités intégrée à l'image
            argv.addAll(List.of("--skip-db-update", "--skip-java-db-update", "--skip-check-update"));
        }
        argv.add(".");
        return argv;
    }

    @Override
    List<Finding> parse(JsonNode report, Path source) throws ScannerException {
        if (report == null || !report.isObject()) {
            throw new ScannerException("format de rapport Trivy inattendu");
        }
        JsonNode results = report.path("Results");
        if (results.isMissingNode() || results.isNull()) {
            return List.of();
        }
        if (!results.isArray()) {
            throw new ScannerException("format de rapport Trivy inattendu");
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode result : results) {
            String target = relativePath(text(result, "Target", ""), source);
            for (JsonNode vuln : result.path("Vulnerabilities")) {
                if (vuln.isObject()) {
                    findings.add(vulnerability(target, vuln));
                }
            }
            for (JsonNode misconf : result.path("Misconfigurations")) {
                if (misconf.isObject() && "FAIL".equals(text(misconf, "Status", "FAIL"))) {
                    findings.add(new Finding("trivy", text(misconf, "ID", "?"), severity(misconf), target,
                            positiveInt(misconf.path("CauseMetadata"), "StartLine"), text(misconf, "Title", "")));
                }
            }
        }
        return findings;
    }

    private static Finding vulnerability(String target, JsonNode vuln) {
        String fixed = text(vuln, "FixedVersion", "");
        String title = text(vuln, "Title", "");
        String message = text(vuln, "PkgName", "?") + " " + text(vuln, "InstalledVersion", "?")
                + (fixed.isEmpty() ? " (pas de correctif)" : " → " + fixed) + (title.isEmpty() ? "" : " : " + title);
        return new Finding("trivy", text(vuln, "VulnerabilityID", "?"), severity(vuln), target, null, message);
    }

    private static String severity(JsonNode node) {
        return text(node, "Severity", "UNKNOWN").toUpperCase(Locale.ROOT);
    }
}
