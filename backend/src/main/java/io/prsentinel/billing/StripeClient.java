package io.prsentinel.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Les trois appels Stripe utiles (Checkout, portail client, lecture d'abonnement) et la vérification des webhooks. */
public class StripeClient {

    /** Version d'API figée : les champs lus ici ne changent pas sous nos pieds. */
    static final String API_VERSION = "2024-06-20";
    static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public static class StripeException extends RuntimeException {
        public StripeException(String message) {
            super(message);
        }
    }

    private final BillingSettings settings;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public StripeClient(BillingSettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
    }

    /**
     * Session Checkout de l'offre Équipe pour une installation ; renvoie l'URL de paiement hébergée par Stripe.
     * L'essai gratuit n'est offert qu'une fois par installation ({@code trial}).
     */
    public String createCheckout(long githubInstallationId, String compte, int seats, String existingCustomer,
            boolean trial) {
        List<Map.Entry<String, String>> f = new ArrayList<>();
        add(f, "mode", "subscription");
        add(f, "line_items[0][price]", settings.priceEquipe());
        add(f, "line_items[0][quantity]", String.valueOf(seats));
        add(f, "line_items[0][adjustable_quantity][enabled]", "true");
        add(f, "line_items[0][adjustable_quantity][minimum]", "1");
        add(f, "line_items[0][adjustable_quantity][maximum]", "500");
        add(f, "client_reference_id", String.valueOf(githubInstallationId));
        add(f, "metadata[installation_id]", String.valueOf(githubInstallationId));
        // Recopiée sur l'abonnement : c'est elle qui relie chaque événement d'abonnement à l'installation.
        add(f, "subscription_data[metadata][installation_id]", String.valueOf(githubInstallationId));
        add(f, "subscription_data[metadata][compte]", compte);
        if (trial && settings.trialDays() > 0) {
            add(f, "subscription_data[trial_period_days]", String.valueOf(settings.trialDays()));
        }
        if (existingCustomer != null) {
            add(f, "customer", existingCustomer);
        }
        add(f, "allow_promotion_codes", "true");
        add(f, "billing_address_collection", "required");
        add(f, "tax_id_collection[enabled]", "true");
        add(f, "success_url", settings.frontUrl() + "/console/facturation?statut=ok");
        add(f, "cancel_url", settings.frontUrl() + "/console/facturation?statut=annule");
        return url(post("/v1/checkout/sessions", f), "https://checkout.stripe.com/");
    }

    /** Portail client Stripe : moyen de paiement, factures, sièges, résiliation. */
    public String createPortal(String customer) {
        List<Map.Entry<String, String>> f = new ArrayList<>();
        add(f, "customer", customer);
        add(f, "return_url", settings.frontUrl() + "/console/facturation");
        return url(post("/v1/billing_portal/sessions", f), "https://billing.stripe.com/");
    }

    public JsonNode subscription(String id) {
        if (!id.matches("sub_[A-Za-z0-9]+")) {
            throw new StripeException("identifiant d'abonnement invalide");
        }
        return send(HttpRequest.newBuilder(URI.create(settings.stripeApi() + "/v1/subscriptions/" + id)).GET());
    }

    private String url(JsonNode session, String expectedPrefix) {
        String url = session.path("url").asText("");
        // L'URL part vers le navigateur : seulement une page Stripe (ou le faux Stripe des tests, même hôte que l'API).
        if (!url.startsWith(expectedPrefix) && !url.startsWith(settings.stripeApi() + "/")) {
            throw new StripeException("URL de session inattendue");
        }
        return url;
    }

    private JsonNode post(String path, List<Map.Entry<String, String>> form) {
        String body = form.stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(settings.stripeApi() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private JsonNode send(HttpRequest.Builder req) {
        try {
            HttpResponse<byte[]> res = http.send(req.timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + settings.secretKey())
                    .header("Stripe-Version", API_VERSION).build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode json = mapper.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                // Message d'erreur Stripe journalisé côté serveur, jamais renvoyé tel quel au navigateur.
                throw new StripeException("Stripe HTTP " + res.statusCode() + " : "
                        + json.path("error").path("message").asText(""));
            }
            return json;
        } catch (IOException e) {
            throw new StripeException("Stripe injoignable : " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StripeException("interrompu");
        }
    }

    /**
     * Vérifie l'en-tête {@code Stripe-Signature} ({@code t=...,v1=...}) : HMAC-SHA256 de {@code t.payload},
     * comparé à temps constant, horodatage à moins de 5 minutes (contre le rejeu).
     */
    public static boolean verifySignature(String header, byte[] payload, String secret, Instant now) {
        if (header == null || secret == null || secret.isBlank()) {
            return false;
        }
        long timestamp = -1;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp < 0 || signatures.isEmpty()
                || Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(SIGNATURE_TOLERANCE) > 0) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            expected = HexFormat.of().formatHex(mac.doFinal(payload)).getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
        boolean ok = false;
        for (String s : signatures) {
            ok |= MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII));
        }
        return ok;
    }

    private static void add(List<Map.Entry<String, String>> form, String k, String v) {
        form.add(Map.entry(k, v));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
