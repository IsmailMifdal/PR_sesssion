package io.prsentinel.scan;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Semgrep (SAST). Les commentaires {@code nosemgrep} de la PR ne masquent rien. */
public class SemgrepScanner extends CommandScanner {

    private final String binary;
    private final String config;

    public SemgrepScanner(String binary, String config, Duration timeout, long maxReportBytes) {
        super(timeout, maxReportBytes);
        this.binary = binary;
        this.config = config;
    }

    @Override
    public String name() {
        return "semgrep";
    }

    @Override
    List<String> command(Path report, Path workdir) {
        List<String> argv = new ArrayList<>(List.of(binary, "scan"));
        for (String c : config.split(",")) {
            argv.add("--config");
            argv.add(c.strip());
        }
        argv.addAll(List.of("--json", "--output", report.toString(), "--metrics=off", "--disable-version-check",
                "--disable-nosem", "--quiet", "."));
        return argv;
    }

    @Override
    List<Finding> parse(JsonNode report, Path source) throws ScannerException {
        if (report == null || !report.isObject() || !report.path("results").isArray()) {
            throw new ScannerException("format de rapport Semgrep inattendu");
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode item : report.get("results")) {
            if (!item.isObject()) {
                continue;
            }
            JsonNode extra = item.path("extra");
            findings.add(new Finding("semgrep", text(item, "check_id", "?"),
                    text(extra, "severity", "UNKNOWN").toUpperCase(Locale.ROOT),
                    relativePath(text(item, "path", ""), source), positiveInt(item.path("start"), "line"),
                    text(extra, "message", "")));
        }
        return findings;
    }
}
