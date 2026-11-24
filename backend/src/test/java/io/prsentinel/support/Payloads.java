package io.prsentinel.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fabriques de payloads {@code pull_request} pour les tests. */
public final class Payloads {

    public static final String SHA_A = "a".repeat(40);
    public static final String SHA_B = "b".repeat(40);
    public static final String BASE = "0".repeat(40);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Payloads() {}

    public static ObjectNode pullRequest(String action, String sha, int number) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("action", action);
        root.putObject("installation").put("id", 99);
        root.putObject("repository").put("full_name", "acme/widgets");
        ObjectNode pr = root.putObject("pull_request");
        pr.put("number", number);
        pr.putObject("head").put("sha", sha);
        pr.putObject("base").put("sha", BASE);
        return root;
    }

    public static ObjectNode pullRequest() {
        return pullRequest("opened", SHA_A, 7);
    }

    public static byte[] bytes(Object node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
