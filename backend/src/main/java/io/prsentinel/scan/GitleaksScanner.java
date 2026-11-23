package io.prsentinel.scan;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Gitleaks (secrets). Configuration de confiance et liste d'exclusion vide : celles de la PR sont ignorées.
 * Seuls la règle et l'emplacement sont conservés, jamais {@code Secret} ni {@code Match}.
 */
public class GitleaksScanner extends CommandScanner {

    private final String binary;

    public GitleaksScanner(String binary, Duration timeout, long maxReportBytes) {
        super(timeout, maxReportBytes);
        this.binary = binary;
    }

    @Override
    public String name() {
        return "gitleaks";
    }

    @Override
    List<String> command(Path report, Path workdir) throws IOException {
        Path config = workdir.resolve("gitleaks.toml");
        try (InputStream in = GitleaksScanner.class.getResourceAsStream("/gitleaks.toml")) {
            Files.copy(in, config, StandardCopyOption.REPLACE_EXISTING);
        }
        Path ignoreDir = Files.createDirectories(workdir.resolve("gitleaks-ignore"));
        return List.of(binary, "dir", ".", "--config", config.toString(), "--gitleaks-ignore-path",
                ignoreDir.toString(), "--ignore-gitleaks-allow", "--report-format", "json", "--report-path",
                report.toString(), "--redact", "--no-banner", "--exit-code", "0", "--log-level", "warn");
    }

    @Override
    List<Finding> parse(JsonNode report, Path source) throws ScannerException {
        if (report == null || report.isNull()) {
            return List.of();
        }
        if (!report.isArray()) {
            throw new ScannerException("format de rapport Gitleaks inattendu");
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode item : report) {
            if (!item.isObject()) {
                continue;
            }
            String rule = text(item, "RuleID", "?");
            findings.add(new Finding("gitleaks", rule, "SECRET", relativePath(text(item, "File", ""), source),
                    positiveInt(item, "StartLine"), text(item, "Description", rule)));
        }
        return findings;
    }
}
