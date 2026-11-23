package io.prsentinel.scan;

import static io.prsentinel.support.LocalGit.git;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCheckoutTest {

    @TempDir
    Path tmp;
    Path origin;
    String first;
    String second;

    final GitCheckout fileCheckout = new GitCheckout(Duration.ofSeconds(30), List.of("file"));

    @BeforeEach
    void origin() throws Exception {
        origin = Files.createDirectories(tmp.resolve("origin"));
        git(origin, "init", "--quiet", "-b", "main");
        Files.writeString(origin.resolve("a.txt"), "v1\n");
        git(origin, "add", ".");
        git(origin, "commit", "--quiet", "-m", "one");
        first = git(origin, "rev-parse", "HEAD");
        Files.writeString(origin.resolve("a.txt"), "v2\n");
        Files.writeString(origin.resolve(".git/hooks/post-checkout"), "#!/bin/sh\ntouch HOOK_RAN\n");
        origin.resolve(".git/hooks/post-checkout").toFile().setExecutable(true);
        git(origin, "commit", "--quiet", "-am", "two");
        second = git(origin, "rev-parse", "HEAD");
    }

    @Test
    void checksOutExactShaShallowWithoutHooks() throws Exception {
        Path dest = fileCheckout.checkout("file://" + origin, first, tmp.resolve("dest"), null);
        assertThat(Files.readString(dest.resolve("a.txt"))).isEqualTo("v1\n");
        assertThat(git(dest, "rev-parse", "HEAD")).isEqualTo(first);
        assertThat(git(dest, "rev-list", "--count", "HEAD")).isEqualTo("1");
        assertThat(dest.resolve("HOOK_RAN")).doesNotExist();
    }

    @Test
    void httpsOnlyByDefaultAndUnknownShaFails() {
        assertThatThrownBy(() -> new GitCheckout(Duration.ofSeconds(30))
                .checkout("file://" + origin, first, tmp.resolve("d1"), null))
                .isInstanceOf(GitCheckout.CheckoutException.class).hasMessageContaining("fetch");
        assertThatThrownBy(() -> fileCheckout.checkout("file://" + origin, "f".repeat(40), tmp.resolve("d2"), null))
                .isInstanceOf(GitCheckout.CheckoutException.class);
    }

    @Test
    void scannerOverrideFilesRemoved() throws Exception {
        for (String rel : List.of(".gitleaksignore", ".gitleaks.toml", "sub/.semgrepignore", "trivy.yaml",
                "deep/x/.trivyignore", "keep.toml")) {
            Files.createDirectories(origin.resolve(rel).getParent());
            Files.writeString(origin.resolve(rel), "x\n");
        }
        git(origin, "add", ".");
        git(origin, "commit", "--quiet", "-m", "overrides");
        Path dest = fileCheckout.checkout("file://" + origin, git(origin, "rev-parse", "HEAD"), tmp.resolve("d"), null);
        try (Stream<Path> files = Files.walk(dest)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> dest.relativize(p).toString())
                    .filter(p -> !p.startsWith(".git/")).sorted().toList()).containsExactly("a.txt", "keep.toml");
        }
    }

    @Test
    void tokenOnlyInEnvAndHardenedConfig() {
        Map<String, String> env = new GitCheckout(Duration.ofSeconds(5)).gitEnv("ghs_TOPSECRET");
        int count = Integer.parseInt(env.get("GIT_CONFIG_COUNT"));
        Map<String, String> config = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            config.put(env.get("GIT_CONFIG_KEY_" + i), env.get("GIT_CONFIG_VALUE_" + i));
        }
        assertThat(config).containsEntry("protocol.allow", "never").containsEntry("protocol.https.allow", "always")
                .containsEntry("core.symlinks", "false").containsEntry("transfer.fsckObjects", "true")
                .containsEntry("core.hooksPath", "/dev/null");
        assertThat(config.get("http.extraHeader")).startsWith("Authorization: Basic ").doesNotContain("ghs_TOPSECRET");
        assertThat(env).containsEntry("GIT_TERMINAL_PROMPT", "0");
    }
}
