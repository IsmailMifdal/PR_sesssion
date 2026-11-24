package io.prsentinel.webhook;

import static io.prsentinel.support.Payloads.BASE;
import static io.prsentinel.support.Payloads.SHA_A;
import static io.prsentinel.support.Payloads.pullRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PullRequestEventParserTest {

    @Test
    void parsesScannableAction() {
        NewJob job = PullRequestEventParser.parse("abc-123", pullRequest("synchronize", SHA_A, 7)).orElseThrow();
        assertThat(job).isEqualTo(new NewJob("abc-123", 99, "acme/widgets", 7, SHA_A, BASE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "labeled", "edited", "assigned"})
    void ignoresOtherActions(String action) {
        assertThat(PullRequestEventParser.parse("abc", pullRequest(action, SHA_A, 7))).isEmpty();
    }

    static Stream<Consumer<ObjectNode>> malformed() {
        return Stream.of(
                p -> p.remove("installation"),
                p -> ((ObjectNode) p.get("pull_request").get("head")).put("sha", "not-a-sha"),
                p -> ((ObjectNode) p.get("pull_request").get("head")).put("sha", "A".repeat(40)),
                p -> ((ObjectNode) p.get("pull_request").get("base")).remove("sha"),
                p -> ((ObjectNode) p.get("pull_request")).put("number", 0),
                p -> ((ObjectNode) p.get("pull_request")).put("number", true),
                p -> ((ObjectNode) p.get("pull_request")).put("number", 1L << 40),
                p -> ((ObjectNode) p.get("repository")).put("full_name", "../etc/passwd"),
                p -> ((ObjectNode) p.get("repository")).put("full_name", "acme/../x"),
                p -> ((ObjectNode) p.get("repository")).put("full_name", "acme"),
                p -> ((ObjectNode) p.get("installation")).put("id", "99"));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void rejectsMalformed(Consumer<ObjectNode> mutate) {
        ObjectNode payload = pullRequest();
        mutate.accept(payload);
        assertThatThrownBy(() -> PullRequestEventParser.parse("abc", payload))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "a/b"})
    void rejectsBadDeliveryId(String delivery) {
        assertThatThrownBy(() -> PullRequestEventParser.parse(delivery, pullRequest()))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void rejectsNonObject() {
        assertThatThrownBy(() -> PullRequestEventParser.parse("abc", JsonNodeFactory.instance.arrayNode()))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> PullRequestEventParser.parse("x".repeat(101), pullRequest()))
                .isInstanceOf(InvalidPayloadException.class);
    }
}
