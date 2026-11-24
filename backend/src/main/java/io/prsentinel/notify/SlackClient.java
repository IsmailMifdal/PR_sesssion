package io.prsentinel.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Envoi vers un webhook entrant Slack. Seules les URL Slack sont acceptées (pas de requête vers une adresse
 * arbitraire fournie par un client : pas de SSRF), sans suivre de redirection.
 */
public class SlackClient {

    public static class SlackException extends RuntimeException {
        public SlackException(String message) {
            super(message);
        }
    }

    private final String prefix;
    private final Pattern valid;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** @param prefix {@code https://hooks.slack.com/services/} en production */
    public SlackClient(String prefix, HttpClient http) {
        this.prefix = prefix;
        this.valid = Pattern.compile(Pattern.quote(prefix) + "[A-Za-z0-9]+/[A-Za-z0-9]+/[A-Za-z0-9]+");
        this.http = http;
    }

    public boolean isValidWebhook(String url) {
        return url != null && url.length() <= 300 && valid.matcher(url).matches();
    }

    /** Message au format mrkdwn de Slack ; le texte doit déjà être échappé ({@link #escape}). */
    public void post(String webhookUrl, String text) {
        if (!isValidWebhook(webhookUrl)) {
            throw new SlackException("URL de webhook Slack invalide");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(Map.of("text", text))))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                throw new SlackException("Slack a répondu " + res.statusCode());
            }
        } catch (IOException e) {
            throw new SlackException("Slack injoignable : " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlackException("interrompu");
        }
    }

    /** Échappement mrkdwn : un nom de dépôt ou de fichier ne peut pas injecter de lien ni de mention. */
    public static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public String prefix() {
        return prefix;
    }
}
