package io.prsentinel.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scanner en ligne de commande produisant un rapport JSON. */
abstract class CommandScanner implements Scanner {

    private static final Logger log = LoggerFactory.getLogger(CommandScanner.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    protected final Duration timeout;
    protected final long maxReportBytes;

    CommandScanner(Duration timeout, long maxReportBytes) {
        this.timeout = timeout;
        this.maxReportBytes = maxReportBytes;
    }

    abstract List<String> command(Path report, Path workdir) throws IOException;

    abstract List<Finding> parse(JsonNode report, Path source) throws ScannerException;

    String reportName() {
        return name() + ".json";
    }

    Duration processTimeout() {
        return timeout;
    }

    @Override
    public ScanResult scan(Path source, Path workdir) {
        Path report = workdir.resolve(reportName());
        try {
            List<String> argv = command(report, workdir);
            BoundedProcess.Result result = BoundedProcess.run(argv, source, BoundedProcess.scrubbedEnv(),
                    processTimeout());
            if (result.exitCode() != 0) {
                log.warn("{} a échoué (code {}) : {}", name(), result.exitCode(), result.stderr());
                return ScanResult.failed(name(), "code de sortie " + result.exitCode());
            }
            return ScanResult.ok(name(), parse(load(report), source));
        } catch (BoundedProcess.TimeoutException e) {
            return ScanResult.failed(name(), e.getMessage());
        } catch (BoundedProcess.LaunchException e) {
            return ScanResult.failed(name(), "exécutable introuvable : " + name());
        } catch (ScannerException e) {
            return ScanResult.failed(name(), e.getMessage());
        } catch (IOException e) {
            return ScanResult.failed(name(), "erreur d'entrée/sortie");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScanResult.failed(name(), "interrompu");
        }
    }

    JsonNode load(Path report) throws ScannerException {
        try {
            if (Files.size(report) > maxReportBytes) {
                throw new ScannerException("rapport trop volumineux");
            }
            return MAPPER.readTree(report.toFile());
        } catch (NoSuchFileException e) {
            throw new ScannerException("rapport absent", e);
        } catch (IOException e) {
            throw new ScannerException("rapport JSON illisible", e);
        }
    }

    /** Chemin relatif au dépôt cloné : jamais de chemin absolu du worker dans un commentaire. */
    static String relativePath(String raw, Path source) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            Path root = source.toAbsolutePath().normalize();
            Path normalized = path.normalize();
            return normalized.startsWith(root) ? root.relativize(normalized).toString()
                    : String.valueOf(normalized.getFileName());
        }
        String s = path.normalize().toString();
        return s.startsWith("./") ? s.substring(2) : s;
    }

    static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.textValue() : fallback;
    }

    static Integer positiveInt(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isIntegralNumber() && v.intValue() > 0 ? v.intValue() : null;
    }
}
