package io.prsentinel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prsentinel.auth.SessionStore.GitHubUser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Appels GitHub de la connexion : échange du code OAuth, profil, dépôts accessibles via les installations de
 * l'app. Le jeton utilisateur ne sert que pendant la connexion : il n'est jamais stocké.
 */
public class GitHubOAuthClient {

    static final int MAX_PAGES = 10;
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public static class OAuthException extends RuntimeException {
        public OAuthException(String message) {
            super(message);
        }
    }

    private final AuthSettings settings;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubOAuthClient(AuthSettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
    }

    public String authorizeUrl(String state) {
        return settings.oauthUrl() + "/login/oauth/authorize?" + form(Map.of(
                "client_id", settings.clientId(), "redirect_uri", settings.callbackUrl(), "state", state,
                "allow_signup", "false"));
    }

    public String exchangeCode(String code) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.oauthUrl() + "/login/oauth/access_token"))
                .timeout(TIMEOUT).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("client_id", settings.clientId(),
                        "client_secret", settings.clientSecret(), "code", code,
                        "redirect_uri", settings.callbackUrl()))))
                .build();
        JsonNode body = send(req).json;
        String token = body.path("access_token").asText("");
        if (token.isEmpty()) {
            // GitHub répond 200 avec {"error": ...} : code expiré, déjà utilisé, mauvais secret...
            throw new OAuthException("échange du code refusé : " + body.path("error").asText("réponse invalide"));
        }
        return token;
    }

    public GitHubUser user(String token) {
        JsonNode u = get(token, settings.apiUrl() + "/user").json;
        long id = u.path("id").asLong(0);
        String login = u.path("login").asText("");
        if (id <= 0 || login.isEmpty()) {
            throw new OAuthException("profil GitHub invalide");
        }
        return new GitHubUser(id, login, u.path("name").isTextual() ? u.path("name").asText() : null,
                u.path("avatar_url").asText(null));
    }

    /** Dépôts des installations de l'app auxquels l'utilisateur a accès (« owner/repo »). */
    public List<String> accessibleRepos(String token) {
        List<String> repos = new ArrayList<>();
        for (JsonNode inst : paginate(token, settings.apiUrl() + "/user/installations?per_page=100", "installations")) {
            long id = inst.path("id").asLong(0);
            if (id <= 0) {
                continue;
            }
            for (JsonNode r : paginate(token,
                    settings.apiUrl() + "/user/installations/" + id + "/repositories?per_page=100", "repositories")) {
                String name = r.path("full_name").asText("");
                if (name.matches("[\\w.-]+/[\\w.-]+")) {
                    repos.add(name);
                }
            }
        }
        return repos;
    }

    private List<JsonNode> paginate(String token, String first, String field) {
        List<JsonNode> items = new ArrayList<>();
        String url = first;
        for (int page = 0; url != null && page < MAX_PAGES; page++) {
            Response res = get(token, url);
            res.json.path(field).forEach(items::add);
            url = res.next;
            // Le jeton ne part jamais ailleurs que vers l'API configurée.
            if (url != null && !url.startsWith(settings.apiUrl() + "/")) {
                url = null;
            }
        }
        return items;
    }

    private record Response(JsonNode json, String next) {}

    private Response get(String token, String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json").header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build());
    }

    private Response send(HttpRequest req) {
        try {
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                throw new OAuthException("GitHub a répondu " + res.statusCode() + " pour " + req.uri().getPath());
            }
            String next = res.headers().firstValue("Link").map(l -> {
                Matcher m = NEXT_LINK.matcher(l);
                return m.find() ? m.group(1) : null;
            }).orElse(null);
            return new Response(mapper.readTree(res.body()), next);
        } catch (IOException e) {
            throw new OAuthException("GitHub injoignable : " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("interrompu");
        }
    }

    private static String form(Map<String, String> params) {
        return new LinkedHashMap<>(params).entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
