package io.prsentinel.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Extrait et valide les champs utiles d'un événement {@code pull_request}. */
public final class PullRequestEventParser {

    public static final Set<String> SCANNED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}$");
    private static final Pattern DELIVERY = Pattern.compile("^[A-Za-z0-9-]{1,100}$");

    private PullRequestEventParser() {}

    public static Optional<NewJob> parse(String deliveryId, JsonNode payload) {
        if (deliveryId == null || !DELIVERY.matcher(deliveryId).matches()) {
            throw new InvalidPayloadException("X-GitHub-Delivery invalide");
        }
        if (payload == null || !payload.isObject()) {
            throw new InvalidPayloadException("payload JSON objet attendu");
        }
        if (!SCANNED_ACTIONS.contains(text(payload, "action"))) {
            return Optional.empty();
        }
        long installationId = positiveLong(payload, "installation", "id");
        String repo = text(payload, "repository", "full_name");
        long number = positiveLong(payload, "pull_request", "number");
        String head = text(payload, "pull_request", "head", "sha");
        String base = text(payload, "pull_request", "base", "sha");

        if (!REPO.matcher(repo).matches() || repo.contains("..")) {
            throw new InvalidPayloadException("repository.full_name invalide");
        }
        if (number > Integer.MAX_VALUE) {
            throw new InvalidPayloadException("pull_request.number invalide");
        }
        if (!SHA.matcher(head).matches()) {
            throw new InvalidPayloadException("pull_request.head.sha invalide");
        }
        if (!SHA.matcher(base).matches()) {
            throw new InvalidPayloadException("pull_request.base.sha invalide");
        }
        return Optional.of(new NewJob(deliveryId, installationId, repo, (int) number, head, base));
    }

    private static JsonNode node(JsonNode root, String... path) {
        JsonNode current = root;
        for (String key : path) {
            current = current == null ? null : current.get(key);
        }
        if (current == null || current.isNull()) {
            throw new InvalidPayloadException("champ manquant : " + String.join(".", path));
        }
        return current;
    }

    private static String text(JsonNode root, String... path) {
        JsonNode value = node(root, path);
        if (!value.isTextual()) {
            throw new InvalidPayloadException("texte attendu : " + String.join(".", path));
        }
        return value.textValue();
    }

    private static long positiveLong(JsonNode root, String... path) {
        JsonNode value = node(root, path);
        if (!value.isIntegralNumber() || value.longValue() <= 0) {
            throw new InvalidPayloadException("entier positif attendu : " + String.join(".", path));
        }
        return value.longValue();
    }
}
