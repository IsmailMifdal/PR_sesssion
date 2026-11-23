package io.prsentinel.support;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Dépôt git local servant d'« origin » dans les tests. */
public final class LocalGit {

    private LocalGit() {}

    public static String git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.test",
                "-c", "commit.gpgsign=false"));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " : " + out);
        }
        return out;
    }
}
