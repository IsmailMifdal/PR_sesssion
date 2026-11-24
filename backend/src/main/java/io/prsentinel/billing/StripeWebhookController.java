package io.prsentinel.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prsentinel.billing.StripeClient.StripeException;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook Stripe. Signature vérifiée sur le corps brut ; le contenu de l'événement ne sert qu'à savoir quel
 * abonnement relire : l'état enregistré vient toujours d'une lecture fraîche de l'API Stripe, ce qui rend le
 * traitement idempotent et insensible à l'ordre d'arrivée des événements.
 */
@RestController
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    static final int MAX_BODY_BYTES = 512 * 1024;
    static final Set<String> HANDLED = Set.of("checkout.session.completed", "customer.subscription.created",
            "customer.subscription.updated", "customer.subscription.deleted", "customer.subscription.paused",
            "customer.subscription.resumed");

    private final BillingSettings settings;
    private final StripeClient stripe;
    private final SubscriptionStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public StripeWebhookController(BillingSettings settings, StripeClient stripe, SubscriptionStore store) {
        this(settings, stripe, store, Clock.systemUTC());
    }

    StripeWebhookController(BillingSettings settings, StripeClient stripe, SubscriptionStore store, Clock clock) {
        this.settings = settings;
        this.stripe = stripe;
        this.store = store;
        this.clock = clock;
    }

    @PostMapping("/stripe/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        if (!settings.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (body == null || body.length > MAX_BODY_BYTES
                || !StripeClient.verifySignature(signature, body, settings.webhookSecret(), clock.instant())) {
            return ResponseEntity.status(400).build();
        }
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.status(400).build();
        }
        String type = event.path("type").asText("");
        if (!HANDLED.contains(type)) {
            return ResponseEntity.ok().build(); // accusé de réception : Stripe ne renverra pas l'événement
        }
        JsonNode obj = event.path("data").path("object");
        String subscriptionId = type.startsWith("checkout.") ? obj.path("subscription").asText("") : obj.path("id").asText("");
        if (subscriptionId.isEmpty()) {
            return ResponseEntity.ok().build(); // Checkout sans abonnement (paiement unique) : rien à faire
        }
        try {
            JsonNode sub = stripe.subscription(subscriptionId);
            long installation = sub.path("metadata").path("installation_id").asLong(0);
            if (installation <= 0) {
                log.warn("abonnement {} sans installation_id : ignoré", subscriptionId);
                return ResponseEntity.ok().build();
            }
            boolean applied = store.upsert(installation, sub);
            log.info("Stripe {} : abonnement {} de l'installation {} → {}{}", type, subscriptionId, installation,
                    sub.path("status").asText(), applied ? "" : " (ignoré : un autre abonnement est actif)");
            return ResponseEntity.ok().build();
        } catch (StripeException e) {
            // 5xx : Stripe renverra l'événement plus tard.
            log.error("webhook Stripe {} non traité : {}", type, e.getMessage());
            return ResponseEntity.status(503).build();
        }
    }
}
