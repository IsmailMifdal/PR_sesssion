package io.prsentinel.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedProcessTest {

    @TempDir
    Path tmp;

    @Test
    void exitCodeAndSanitizedStderrTail() throws Exception {
        var r = BoundedProcess.run(List.of("sh", "-c", "printf 'a\\033[31mb\\nc' >&2; exit 4"), tmp, Map.of(),
                Duration.ofSeconds(10));
        assertThat(r.exitCode()).isEqualTo(4);
        assertThat(r.stderr()).isEqualTo("a [31mb c");
    }

    @Test
    void timeoutKillsGrandchildrenHoldingStderr() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> BoundedProcess.run(List.of("sh", "-c", "sleep 60 & sleep 60"), tmp, Map.of(),
                Duration.ofSeconds(1))).isInstanceOf(BoundedProcess.TimeoutException.class)
                .hasMessageContaining("délai dépassé");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void backgroundChildDoesNotBlock() throws Exception {
        long start = System.nanoTime();
        var r = BoundedProcess.run(List.of("sh", "-c", "sleep 60 >/dev/null 2>&1 &"), tmp, Map.of(),
                Duration.ofSeconds(10));
        assertThat(r.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void envIsAllowlisted() {
        assertThat(BoundedProcess.scrubbedEnv().keySet()).allMatch(BoundedProcess.ENV_ALLOWLIST::contains);
        assertThat(BoundedProcess.sanitize("x".repeat(5000))).hasSize(BoundedProcess.MAX_STDERR_CHARS);
    }
}
