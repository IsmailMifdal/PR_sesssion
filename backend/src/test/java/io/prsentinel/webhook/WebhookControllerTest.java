package io.prsentinel.webhook;

import static io.prsentinel.support.Payloads.SHA_A;
import static io.prsentinel.support.Payloads.SHA_B;
import static io.prsentinel.support.Payloads.bytes;
import static io.prsentinel.support.Payloads.pullRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.prsentinel.support.PostgresTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class WebhookControllerTest extends PostgresTestBase {

    static final byte[] SECRET = "test-webhook-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    ResultActions send(byte[] body, String event, String delivery, String signature) throws Exception {
        return mvc.perform(post("/webhooks/github").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-GitHub-Event", event).header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", signature));
    }

    ResultActions send(byte[] body, String event, String delivery) throws Exception {
        return send(body, event, delivery, WebhookSignature.sign(SECRET, body));
    }

    List<String> rows() {
        return jdbc.sql("SELECT head_sha || ':' || status FROM scan_job ORDER BY id").query(String.class).list();
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void unsignedOrBadlySignedRejected() throws Exception {
        byte[] body = bytes(pullRequest());
        mvc.perform(post("/webhooks/github").content(body).header("X-GitHub-Event", "pull_request"))
                .andExpect(status().isUnauthorized());
        send(body, "pull_request", "d-1", WebhookSignature.sign("other".getBytes(), body))
                .andExpect(status().isUnauthorized());
        assertThat(rows()).isEmpty();
    }

    @Test
    void pingAndOtherEvents() throws Exception {
        send("{}".getBytes(), "ping", "d-1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pong"));
        send("{}".getBytes(), "push", "d-2").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ignored"));
        assertThat(rows()).isEmpty();
    }

    @Test
    void pullRequestQueued() throws Exception {
        send(bytes(pullRequest()), "pull_request", "d-1").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"));
        assertThat(rows()).containsExactly(SHA_A + ":queued");
        assertThat(jdbc.sql("SELECT max_attempts FROM scan_job").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void ignoredAction() throws Exception {
        send(bytes(pullRequest("closed", SHA_A, 7)), "pull_request", "d-1")
                .andExpect(jsonPath("$.status").value("ignored"));
        assertThat(rows()).isEmpty();
    }

    @Test
    void redeliveryIsIdempotent() throws Exception {
        byte[] body = bytes(pullRequest());
        send(body, "pull_request", "d-1").andExpect(jsonPath("$.status").value("queued"));
        send(body, "pull_request", "d-1").andExpect(jsonPath("$.status").value("duplicate"));
        send(body, "pull_request", "d-2").andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(rows()).hasSize(1);
    }

    @Test
    void newPushSupersedesQueuedJob() throws Exception {
        send(bytes(pullRequest("opened", SHA_A, 7)), "pull_request", "d-1");
        send(bytes(pullRequest("synchronize", SHA_B, 7)), "pull_request", "d-2");
        assertThat(rows()).containsExactly(SHA_A + ":superseded", SHA_B + ":queued");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"action\":\"opened\"}"})
    void malformedPayload(String body) throws Exception {
        send(body.getBytes(), "pull_request", "d-1").andExpect(status().isBadRequest());
        assertThat(rows()).isEmpty();
    }

    @Test
    void oversizedPayload() throws Exception {
        byte[] body = new byte[2 * 1024 * 1024 + 10];
        send(body, "pull_request", "d-1").andExpect(status().isPayloadTooLarge());
    }

    @Test
    void readBoundedStopsAtLimit() throws Exception {
        assertThat(WebhookController.readBounded(new java.io.ByteArrayInputStream(new byte[11]), 10)).isNull();
        assertThat(WebhookController.readBounded(new java.io.ByteArrayInputStream(new byte[10]), 10)).hasSize(10);
    }
}
