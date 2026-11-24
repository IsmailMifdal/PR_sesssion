package io.prsentinel.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.prsentinel.diff.PullRequestDiff;
import io.prsentinel.diff.UnifiedDiffParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client GitHub App : JWT, jetons d'installation limités, fichiers de PR, commentaire, revue, check run. */
public class GitHubAppClient {

    public static final String MARKER = "<!-- pr-sentinel:report -->";
    /** Permissions minimales du jeton d'installation (limité en plus au seul dépôt de la PR). */
    public static final Map<String, String> TOKEN_PERMISSIONS = Map.of("contents", "read", "pull_requests",
            "write", "checks", "write", "metadata", "read");
    static final int MAX_PAGES = 30;
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(5);
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    private final long appId;
    private final RSAPrivateKey key;
    private final String api;
    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    private record CachedToken(String value, Instant expiresAt) {}

    public GitHubAppClient(long appId, String privateKeyPem, String apiUrl, HttpClient http, Clock clock) {
        if (appId <= 0) {
            throw new IllegalStateException("SENTINEL_GITHUB_APP_ID est requis");
        }
        this.appId = appId;
        this.key = PemKeys.readRsaPrivateKey(privateKeyPem);
        this.api = apiUrl.replaceAll("/+$", "");
        this.http = http;
        this.clock = clock;
    }

    public long appId() {
        return appId;
    }

    // --- Authentification ----------------------------------------------------------------------

    public String appJwt() {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(String.valueOf(appId))
                .issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now.plusSeconds(9 * 60)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("signature du JWT impossible", e);
        }
    }

    /** Jeton d'installation limité à {@code repo} et aux permissions minimales, mis en cache. */
    public String installationToken(long installationId, String repo) {
        String cacheKey = installationId + "|" + repo;
        CachedToken cached = tokens.get(cacheKey);
        if (cached != null && cached.expiresAt().minus(TOKEN_SAFETY_MARGIN).isAfter(clock.instant())) {
            return cached.value();
        }
        Map<String, Object> body = Map.of("repositories", List.of(repo.substring(repo.indexOf('/') + 1)),
                "permissions", TOKEN_PERMISSIONS);
        JsonNode data = send("POST", "/app/installations/" + installationId + "/access_tokens", appJwt(), body)
                .json();
        String token = data.path("token").asText("");
        if (token.isEmpty()) {
            throw new GitHubException("réponse de jeton d'installation invalide", null);
        }
        Instant expires;
        try {
            expires = OffsetDateTime.parse(data.path("expires_at").asText()).toInstant();
        } catch (RuntimeException e) {
            expires = clock.instant().plus(Duration.ofHours(1)); // durée de vie documentée
        }
        tokens.put(cacheKey, new CachedToken(token, expires));
        return token;
    }

    public void invalidateToken(long installationId, String repo) {
        tokens.remove(installationId + "|" + repo);
    }

    // --- Diff de la PR ---------------------------------------------------------------------------

    /** Lignes ajoutées par la PR, fichier par fichier (fichiers supprimés exclus). */
    public PullRequestDiff pullRequestDiff(String token, String repo, int pr) {
        Map<String, Set<Integer>> lines = new LinkedHashMap<>();
        Map<String, String> patches = new LinkedHashMap<>();
        Set<String> wholeFiles = new java.util.LinkedHashSet<>();
        String url = api + "/repos/" + repo + "/pulls/" + pr + "/files?per_page=100";
        for (int page = 0; page < MAX_PAGES && url != null; page++) {
            Response resp = send("GET", url, token, null);
            for (JsonNode file : resp.json()) {
                if ("removed".equals(file.path("status").asText())) {
                    continue;
                }
                String name = file.path("filename").asText();
                String patch = file.path("patch").isTextual() ? file.path("patch").asText() : null;
                if (patch == null) {
                    // Diff trop gros pour l'API : on ne sait pas quelles lignes ont changé, on garde tout.
                    wholeFiles.add(name);
                } else {
                    lines.put(name, UnifiedDiffParser.addedLines(patch));
                    patches.put(name, patch);
                }
            }
            url = resp.next();
        }
        return new PullRequestDiff(lines, patches, wholeFiles);
    }

    // --- Commentaire unique (mode alertes brutes) ------------------------------------------------

    public Optional<Long> findReportComment(String token, String repo, int pr) {
        String url = api + "/repos/" + repo + "/issues/" + pr + "/comments?per_page=100";
        for (int page = 0; page < MAX_PAGES && url != null; page++) {
            Response resp = send("GET", url, token, null);
            for (JsonNode c : resp.json()) {
                if (c.path("performed_via_github_app").path("id").asLong() == appId
                        && c.path("body").asText("").contains(MARKER)) {
                    return Optional.of(c.path("id").asLong());
                }
            }
            url = resp.next();
        }
        return Optional.empty();
    }

    public long upsertReportComment(String token, String repo, int pr, String body) {
        Optional<Long> existing = findReportComment(token, repo, pr);
        Response resp = existing.isPresent()
                ? send("PATCH", "/repos/" + repo + "/issues/comments/" + existing.get(), token, Map.of("body", body))
                : send("POST", "/repos/" + repo + "/issues/" + pr + "/comments", token, Map.of("body", body));
        return resp.json().path("id").asLong();
    }

    // --- Revue et check run -----------------------------------------------------------------------

    /** Commentaire de revue sur une ligne, ou un bloc [startLine, line], du nouveau fichier. */
    public record ReviewComment(String path, Integer startLine, int line, String body) {
        public ReviewComment(String path, int line, String body) {
            this(path, null, line, body);
        }
    }

    public long createReview(String token, String repo, int pr, String commitSha, String body,
            List<ReviewComment> comments) {
        List<Map<String, Object>> payloadComments = comments.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.path());
            if (c.startLine() != null && c.startLine() < c.line()) { // commentaire sur plusieurs lignes
                m.put("start_line", c.startLine());
                m.put("start_side", "RIGHT");
            }
            m.put("line", c.line());
            m.put("side", "RIGHT");
            m.put("body", c.body());
            return m;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commit_id", commitSha);
        payload.put("event", "COMMENT");
        payload.put("body", body);
        payload.put("comments", payloadComments);
        return send("POST", "/repos/" + repo + "/pulls/" + pr + "/reviews", token, payload).json().path("id").asLong();
    }

    public long createCheckRun(String token, String repo, String name, String headSha, boolean success,
            String title, String summary) {
        return createCheckRun(token, repo, name, headSha, success ? "success" : "failure", title, summary);
    }

    /** @param conclusion {@code success}, {@code failure} ou {@code neutral} (revue non effectuée, ex. quota) */
    public long createCheckRun(String token, String repo, String name, String headSha, String conclusion,
            String title, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("head_sha", headSha);
        payload.put("status", "completed");
        payload.put("conclusion", conclusion);
        payload.put("output", Map.of("title", title, "summary", summary));
        return send("POST", "/repos/" + repo + "/check-runs", token, payload).json().path("id").asLong();
    }

    // --- Transport --------------------------------------------------------------------------------

    record Response(int status, JsonNode json, String next) {}

    private Response send(String method, String pathOrUrl, String bearer, Object body) {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : api + pathOrUrl;
        if (!url.startsWith(api + "/")) {
            throw new GitHubException("URL hors de l'API GitHub configurée", null);
        }
        String shortPath = url.substring(api.length());
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "pr-sentinel");
        try {
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            }
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                throw new GitHubException(method + " " + shortPath + " : HTTP " + resp.statusCode(), resp.statusCode());
            }
            JsonNode json = resp.body().length == 0 ? mapper.createObjectNode() : mapper.readTree(resp.body());
            String next = resp.headers().firstValue("Link").map(GitHubAppClient::nextLink).orElse(null);
            return new Response(resp.statusCode(), json, next);
        } catch (IOException e) {
            throw new GitHubException(method + " " + shortPath + " : " + e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(method + " " + shortPath + " : interrompu", null);
        }
    }

    static String nextLink(String header) {
        Matcher m = NEXT_LINK.matcher(header);
        return m.find() ? m.group(1) : null;
    }
}
