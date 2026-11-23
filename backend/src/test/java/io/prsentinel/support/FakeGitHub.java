package io.prsentinel.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Faux serveur GitHub (API REST minimale) pour les tests. */
public class FakeGitHub implements AutoCloseable {

    public record Request(String method, String path, String query, String authorization, JsonNode body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final KeyPair KEYS = generateKeys();

    private final HttpServer server;
    public final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    public final List<ObjectNode> comments = Collections.synchronizedList(new ArrayList<>());
    public final List<JsonNode> reviews = Collections.synchronizedList(new ArrayList<>());
    public final List<JsonNode> checkRuns = Collections.synchronizedList(new ArrayList<>());
    /** Fichiers de la PR : nom → patch unifié. */
    public final Map<String, String> prFiles = Collections.synchronizedMap(new LinkedHashMap<>());
    public final AtomicInteger tokenCalls = new AtomicInteger();
    public volatile String expiresAt = "2099-01-01T00:00:00Z";
    /** Si non nul, toute requête hors jeton renvoie ce code. */
    public volatile Integer failWith;

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static String privateKeyPem() {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        byte[] raw = ex.getRequestBody().readAllBytes();
        JsonNode body = raw.length == 0 ? null : MAPPER.readTree(raw);
        requests.add(new Request(method, path, query, ex.getRequestHeaders().getFirst("Authorization"), body));

        if (path.endsWith("/access_tokens") && method.equals("POST")) {
            int n = tokenCalls.incrementAndGet();
            respond(ex, 201, MAPPER.createObjectNode().put("token", "ghs_inst_" + n).put("expires_at", expiresAt), null);
            return;
        }
        if (failWith != null) {
            respond(ex, failWith, MAPPER.createObjectNode().put("message", "boom"), null);
            return;
        }
        if (path.matches("/repos/.+/pulls/\\d+/files") && method.equals("GET")) {
            ArrayNode files = MAPPER.createArrayNode();
            prFiles.forEach((name, patch) -> files.addObject().put("filename", name).put("status", "modified")
                    .put("patch", patch));
            respond(ex, 200, files, null);
            return;
        }
        if (path.matches("/repos/.+/issues/\\d+/comments")) {
            if (method.equals("GET")) {
                ArrayNode arr = MAPPER.createArrayNode();
                synchronized (comments) {
                    comments.forEach(arr::add);
                }
                respond(ex, 200, arr, null);
            } else {
                ObjectNode c = MAPPER.createObjectNode().put("id", 500 + comments.size()).put("body",
                        body.path("body").asText());
                c.putObject("performed_via_github_app").put("id", 42);
                comments.add(c);
                respond(ex, 201, c, null);
            }
            return;
        }
        if (path.matches("/repos/.+/issues/comments/\\d+") && method.equals("PATCH")) {
            long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            synchronized (comments) {
                for (ObjectNode c : comments) {
                    if (c.path("id").asLong() == id) {
                        c.put("body", body.path("body").asText());
                        respond(ex, 200, c, null);
                        return;
                    }
                }
            }
        }
        if (path.matches("/repos/.+/pulls/\\d+/reviews") && method.equals("POST")) {
            reviews.add(body);
            respond(ex, 200, MAPPER.createObjectNode().put("id", 900 + reviews.size()), null);
            return;
        }
        if (path.matches("/repos/.+/check-runs") && method.equals("POST")) {
            checkRuns.add(body);
            respond(ex, 201, MAPPER.createObjectNode().put("id", 700 + checkRuns.size()), null);
            return;
        }
        respond(ex, 404, MAPPER.createObjectNode().put("message", "Not Found"), null);
    }

    private static void respond(HttpExchange ex, int status, JsonNode json, String link) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (link != null) {
            ex.getResponseHeaders().add("Link", link);
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
