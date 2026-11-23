package io.prsentinel.scan;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Exécution bornée d'un sous-processus, sans shell : environnement minimal, stdout ignoré, stderr dans un
 * fichier (un descendant qui garde le descripteur ne bloque pas l'appelant), arbre de processus tué au timeout.
 */
public final class BoundedProcess {

    public static final int MAX_STDERR_CHARS = 500;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]+");

    /** Variables transmises ; tout le reste (secrets du worker compris) est filtré. */
    public static final Set<String> ENV_ALLOWLIST = Set.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR",
            "SSL_CERT_FILE", "REQUESTS_CA_BUNDLE", "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy",
            "http_proxy", "no_proxy", "TRIVY_CACHE_DIR", "XDG_CACHE_HOME");

    public record Result(int exitCode, String stderr) {}

    /** Le programme n'a pas pu être lancé (introuvable ou non exécutable). */
    public static class LaunchException extends IOException {
        public LaunchException(String program, Throwable cause) {
            super("exécutable introuvable : " + program, cause);
        }
    }

    public static class TimeoutException extends IOException {
        public TimeoutException(Duration timeout) {
            super("délai dépassé (" + timeout.toSeconds() + "s)");
        }
    }

    private BoundedProcess() {}

    public static Map<String, String> scrubbedEnv() {
        var env = new java.util.HashMap<String, String>();
        System.getenv().forEach((k, v) -> {
            if (ENV_ALLOWLIST.contains(k)) {
                env.put(k, v);
            }
        });
        return env;
    }

    public static String sanitize(String text) {
        String clean = CONTROL.matcher(text).replaceAll(" ").strip();
        return clean.length() > MAX_STDERR_CHARS ? clean.substring(clean.length() - MAX_STDERR_CHARS) : clean;
    }

    public static Result run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout)
            throws IOException, InterruptedException {
        File err = Files.createTempFile("pr-sentinel-stderr", ".log").toFile();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(err);
            pb.environment().clear();
            pb.environment().putAll(env);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new LaunchException(argv.getFirst(), e);
            }
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    killTree(process);
                    throw new TimeoutException(timeout);
                }
            } finally {
                killTree(process); // descendants restés en arrière-plan
            }
            return new Result(process.exitValue(), sanitize(tail(err, 4 * MAX_STDERR_CHARS)));
        } finally {
            Files.deleteIfExists(err.toPath());
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(File file, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, raf.length() - maxBytes);
            byte[] buf = new byte[(int) (raf.length() - start)];
            raf.seek(start);
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
