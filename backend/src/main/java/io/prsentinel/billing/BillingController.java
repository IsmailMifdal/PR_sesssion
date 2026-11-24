package io.prsentinel.billing;

import io.prsentinel.auth.Viewer;
import io.prsentinel.billing.StripeClient.StripeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Facturation vue du tableau de bord (derrière le filtre d'authentification de {@code /api}). Un utilisateur
 * ne peut acheter ou gérer que l'abonnement d'une installation à laquelle il a accès sur GitHub.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);
    static final int MAX_SEATS = 500;

    private final BillingSettings settings;
    private final SubscriptionStore store;
    private final StripeClient stripe;

    public BillingController(BillingSettings settings, SubscriptionStore store, StripeClient stripe) {
        this.settings = settings;
        this.store = store;
        this.stripe = stripe;
    }

    @GetMapping
    public Map<String, Object> billing(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        List<Map<String, Object>> comptes = store.accounts(viewer.admin(), viewer.userId()).stream().map(a -> {
            var sub = store.find(a.githubInstallationId());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("installationId", a.githubInstallationId());
            c.put("compte", a.compte());
            c.put("offre", sub.map(SubscriptionStore.Subscription::offre).orElse(Plans.DECOUVERTE));
            c.put("statut", sub.map(SubscriptionStore.Subscription::statut).orElse(null));
            c.put("sieges", sub.map(SubscriptionStore.Subscription::sieges).orElse(null));
            c.put("finPeriode", sub.map(SubscriptionStore.Subscription::finPeriode).orElse(null));
            c.put("annulationFinPeriode", sub.map(SubscriptionStore.Subscription::annulationFinPeriode).orElse(false));
            c.put("revuesCeMois", store.reviewsThisMonth(a.githubInstallationId()));
            c.put("quota", sub.map(s -> Plans.isPaid(s.statut())).orElse(false) ? null : settings.freeReviewsPerMonth());
            c.put("gerable", sub.isPresent());
            c.put("essaiDisponible", sub.isEmpty());
            return c;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actif", settings.enabled());
        out.put("quotaDecouverte", settings.freeReviewsPerMonth());
        out.put("joursEssai", settings.trialDays());
        out.put("comptes", comptes);
        return out;
    }

    public record CheckoutRequest(long installationId, Integer sieges) {}

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> checkout(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @RequestBody CheckoutRequest req) {
        if (!settings.enabled()) {
            return ResponseEntity.notFound().build();
        }
        var account = store.account(viewer.admin(), viewer.userId(), req.installationId());
        if (account.isEmpty()) {
            return ResponseEntity.status(403).build();
        }
        var existing = store.find(req.installationId());
        if (existing.map(s -> Plans.isPaid(s.statut())).orElse(false)) {
            // Déjà abonné : un second abonnement ferait payer deux fois ; on passe par le portail.
            return ResponseEntity.status(409).body(Map.of("erreur", "deja-abonne"));
        }
        int seats = Math.clamp(req.sieges() == null ? 1 : req.sieges(), 1, MAX_SEATS);
        try {
            String url = stripe.createCheckout(req.installationId(), account.get().compte(), seats,
                    existing.map(SubscriptionStore.Subscription::customerId).orElse(null), existing.isEmpty());
            log.info("Checkout ouvert par {} pour {} ({} siège(s))", viewer.login(), account.get().compte(), seats);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (StripeException e) {
            log.error("Checkout impossible : {}", e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    public record PortalRequest(long installationId) {}

    @PostMapping("/portal")
    public ResponseEntity<Map<String, String>> portal(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @RequestBody PortalRequest req) {
        if (!settings.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (store.account(viewer.admin(), viewer.userId(), req.installationId()).isEmpty()) {
            return ResponseEntity.status(403).build();
        }
        var sub = store.find(req.installationId());
        if (sub.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(Map.of("url", stripe.createPortal(sub.get().customerId())));
        } catch (StripeException e) {
            log.error("portail client impossible : {}", e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }
}
