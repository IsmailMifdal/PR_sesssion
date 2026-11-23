package io.prsentinel.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final byte[] SECRET = "s3cret-s3cret-s3cret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignatureAccepted() {
        assertThat(WebhookSignature.verify(SECRET, BODY, WebhookSignature.sign(SECRET, BODY))).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sha1=0000000000000000000000000000000000000000",
            "sha256=0000000000000000000000000000000000000000000000000000000000000000", "garbage"})
    void invalidHeadersRejected(String header) {
        assertThat(WebhookSignature.verify(SECRET, BODY, header)).isFalse();
    }

    @Test
    void wrongSecretTamperedBodyOrUppercaseRejected() {
        String good = WebhookSignature.sign(SECRET, BODY);
        assertThat(WebhookSignature.verify("other".getBytes(), BODY, good)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "{}".getBytes(), good)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, BODY, good.toUpperCase())).isFalse();
    }
}
