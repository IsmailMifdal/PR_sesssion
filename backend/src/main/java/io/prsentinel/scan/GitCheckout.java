package io.prsentinel.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Récupère exactement le commit de tête de la PR (clone superficiel). Le code n'est jamais exécuté :
 * pas de hooks, pas de sous-modules, pas de liens symboliques, protocoles limités, fsck. Le jeton passe
 * par l'environnement git, jamais dans argv ni dans l'URL.
 */
public class GitCheckout {

    /** Fichiers par lesquels une PR pourrait désactiver ou reconfigurer les scanners. */
    public static final Set<String> SCANNER_OVERRIDE_FILES = Set.of(".semgrepignore", ".gitleaks.toml",
            ".gitleaksignore", "trivy.yaml", "trivy.yml", ".trivyignore", ".trivyignore.yaml");

    public static class CheckoutException extends IOException {
        public CheckoutException(String message) {
            super(message);
        }
    }

    private final Duration timeout;
    private final List<String> allowedProtocols;

    public GitCheckout(Duration timeout) {
        this(timeout, List.of("https"));
    }

    public GitCheckout(Duration timeout, List<String> allowedProtocols) {
        this.timeout = timeout;
        this.allowedProtocols = List.copyOf(allowedProtocols);
    }

    public Path checkout(String cloneUrl, String sha, Path dest, String token) throws IOException,
            InterruptedException {
        Files.createDirectories(dest.getParent());
        Files.createDirectory(dest);
        Map<String, String> env = gitEnv(token);
        git(env, dest, "init", "--quiet");
        git(env, dest, "remote", "add", "origin", cloneUrl);
        git(env, dest, "fetch", "--quiet", "--depth", "1", "--no-tags", "origin", sha);
        git(env, dest, "checkout", "--quiet", "--detach", "FETCH_HEAD");
        stripScannerOverrides(dest);
        return dest;
    }

    Map<String, String> gitEnv(String token) {
        List<String[]> config = new ArrayList<>();
        config.add(new String[] {"protocol.allow", "never"});
        for (String p : allowedProtocols) {
            config.add(new String[] {"protocol." + p + ".allow", "always"});
        }
        config.add(new String[] {"core.hooksPath", "/dev/null"});
        config.add(new String[] {"core.symlinks", "false"});
        config.add(new String[] {"credential.helper", ""});
        config.add(new String[] {"submodule.recurse", "false"});
        config.add(new String[] {"transfer.fsckObjects", "true"});
        config.add(new String[] {"fetch.fsckObjects", "true"});
        if (token != null && !token.isEmpty()) {
            String basic = Base64.getEncoder()
                    .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            config.add(new String[] {"http.extraHeader", "Authorization: Basic " + basic});
        }
        Map<String, String> env = BoundedProcess.scrubbedEnv();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_COUNT", String.valueOf(config.size()));
        for (int i = 0; i < config.size(); i++) {
            env.put("GIT_CONFIG_KEY_" + i, config.get(i)[0]);
            env.put("GIT_CONFIG_VALUE_" + i, config.get(i)[1]);
        }
        return env;
    }

    private void git(Map<String, String> env, Path cwd, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        BoundedProcess.Result result;
        try {
            result = BoundedProcess.run(argv, cwd, env, timeout);
        } catch (BoundedProcess.TimeoutException e) {
            throw new CheckoutException("git " + args[0] + " : " + e.getMessage());
        }
        if (result.exitCode() != 0) {
            String err = result.stderr();
            throw new CheckoutException("git " + args[0] + " a échoué (" + result.exitCode() + ") : "
                    + err.substring(Math.max(0, err.length() - 300)));
        }
    }

    /** Supprime de l'arbre cloné les fichiers de configuration/exclusion des scanners. */
    public static List<String> stripScannerOverrides(Path root) throws IOException {
        List<String> removed = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.getFileName() != null && dir.getFileName().toString().equals(".git") && !dir.equals(root)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (SCANNER_OVERRIDE_FILES.contains(file.getFileName().toString())) {
                    Files.delete(file);
                    removed.add(root.relativize(file).toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return removed;
    }
}
