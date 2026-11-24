package io.prsentinel.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SuggestionIndentTest {

    @Test
    void trimmedFirstLineGetsOriginalIndent() {
        // Cas réel de la PR #3 : 1re ligne rognée par le modèle, 2e ligne indentée.
        String fix = "String sql = \"?\";\n        return q(sql);";
        assertThat(AgentReviewPublisher.reindent(fix, "        "))
                .isEqualTo("        String sql = \"?\";\n        return q(sql);");
    }

    @Test
    void flushBlockFullyReindentedAndIndentedBlockKept() {
        assertThat(AgentReviewPublisher.reindent("a();\nb();", "    ")).isEqualTo("    a();\n    b();");
        assertThat(AgentReviewPublisher.reindent("    a();\n    b();", "    ")).isEqualTo("    a();\n    b();");
        assertThat(AgentReviewPublisher.reindent("a();", "")).isEqualTo("a();");
        assertThat(AgentReviewPublisher.leadingWhitespace("\t  x")).isEqualTo("\t  ");
    }
}
