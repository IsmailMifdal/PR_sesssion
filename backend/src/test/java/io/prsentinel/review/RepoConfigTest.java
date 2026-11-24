package io.prsentinel.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prsentinel.scan.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepoConfigTest {

    static Finding f(String tool, String path) {
        return new Finding(tool, "r", "ERROR", path, 1, "m");
    }

    @Test
    void ignoresMatchingPathsButNeverSecrets() {
        RepoConfig c = RepoConfig.parse("ignorer:\n  - \"eval/**\"\n  - ./vendor/**\n");
        assertThat(c.ignore()).containsExactly("eval/**", "vendor/**");
        assertThat(c.ignores(f("semgrep", "eval/cases/01-sqli/pr/A.java"))).isTrue();
        assertThat(c.ignores(f("trivy", "vendor/lib/pom.xml"))).isTrue();
        assertThat(c.ignores(f("semgrep", "src/main/java/App.java"))).isFalse();
        assertThat(c.ignores(f("semgrep", "evaluation/A.java"))).isFalse();
        assertThat(c.ignores(f("gitleaks", "eval/cases/x/application.yml"))).isFalse(); // secret : toujours signalé
        assertThat(c.keep(List.of(f("semgrep", "eval/a/B.java"), f("semgrep", "src/C.java"))))
                .extracting(Finding::path).containsExactly("src/C.java");
    }

    @Test
    void emptyOrMissingConfigIgnoresNothing() {
        assertThat(RepoConfig.parse(null)).isEqualTo(RepoConfig.EMPTY);
        assertThat(RepoConfig.parse("# rien\n")).isEqualTo(RepoConfig.EMPTY);
        assertThat(RepoConfig.parse("autre: 1\n").ignores(f("semgrep", "eval/A.java"))).isFalse();
    }

    @Test
    void invalidConfigIsRejected() {
        assertThatThrownBy(() -> RepoConfig.parse("ignorer: eval/**")).isInstanceOf(RepoConfig.InvalidConfigException.class);
        assertThatThrownBy(() -> RepoConfig.parse("- a\n- b")).isInstanceOf(RepoConfig.InvalidConfigException.class);
        assertThatThrownBy(() -> RepoConfig.parse("ignorer: [1, 2]")).isInstanceOf(RepoConfig.InvalidConfigException.class);
        assertThatThrownBy(() -> RepoConfig.parse("ignorer: [\"a\"\n")).isInstanceOf(RepoConfig.InvalidConfigException.class);
        assertThatThrownBy(() -> RepoConfig.parse("!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[]]]"))
                .isInstanceOf(RepoConfig.InvalidConfigException.class); // pas de construction d'objets arbitraires
        assertThatThrownBy(() -> RepoConfig.parse("ignorer:\n" + "  - x\n".repeat(51)))
                .isInstanceOf(RepoConfig.InvalidConfigException.class);
    }
}
