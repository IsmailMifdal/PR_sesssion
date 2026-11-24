package io.prsentinel.notify;

import io.prsentinel.auth.Viewer;
import io.prsentinel.billing.SubscriptionStore;
import io.prsentinel.notify.NotifyConfig.NotifySettings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réglages des notifications d'un compte GitHub (installation), par une personne qui y a accès. L'URL Slack est
 * en écriture seule : elle n'est jamais renvoyée, seulement ses 4 derniers caractères.
 */
@RestController
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);
    static final int MAX_EMAILS = 10;
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}");
    /** Envois de test : au plus un par minute et par compte (contre l'envoi de masse vers des tiers). */
    private final Map<String, Instant> lastTest = new ConcurrentHashMap<>();

    private final NotificationStore store;
    private final SubscriptionStore accounts;
    private final NotifySettings settings;
    private final SlackClient slack;
    private final Mailer mailer;
    private final WeeklyReport weekly;

    public NotificationController(NotificationStore store, SubscriptionStore accounts, NotifySettings settings,
            SlackClient slack, Mailer mailer, WeeklyReport weekly) {
        this.store = store;
        this.accounts = accounts;
        this.settings = settings;
        this.slack = slack;
        this.mailer = mailer;
        this.weekly = weekly;
    }

    @GetMapping("/api/notifications")
    public Map<String, Object> list(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        List<Map<String, Object>> comptes = new ArrayList<>();
        for (var a : accounts.accounts(viewer.admin(), viewer.userId())) {
            var s = store.get(a.githubInstallationId());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("installationId", a.githubInstallationId());
            c.put("compte", a.compte());
            c.put("slackConfigure", s.slackSealed() != null);
            c.put("slackIndice", s.slackHint());
            c.put("slackNiveau", s.slackLevel());
            c.put("emails", s.emails());
            c.put("rapportHebdo", s.weekly());
            comptes.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slackActif", settings.slackEnabled());
        out.put("emailActif", mailer.enabled());
        out.put("comptes", comptes);
        return out;
    }

    /** @param slackWebhook {@code null} : inchangé ; chaîne vide : supprimé ; sinon nouvelle URL Slack */
    public record Update(String slackWebhook, String slackNiveau, List<String> emails, Boolean rapportHebdo) {}

    @PutMapping("/api/notifications/{installationId}")
    public ResponseEntity<Map<String, String>> update(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @PathVariable long installationId, @RequestBody Update req) {
        if (accounts.account(viewer.admin(), viewer.userId(), installationId).isEmpty()) {
            return ResponseEntity.status(403).build();
        }
        var current = store.get(installationId);
        String sealed = current.slackSealed();
        String hint = current.slackHint();
        if (req.slackWebhook() != null) {
            String url = req.slackWebhook().strip();
            if (url.isEmpty()) {
                sealed = null;
                hint = null;
            } else if (!settings.slackEnabled()) {
                return ResponseEntity.status(409).body(Map.of("erreur", "slack-desactive"));
            } else if (!slack.isValidWebhook(url)) {
                return ResponseEntity.badRequest().body(Map.of("erreur", "slack-url"));
            } else {
                sealed = settings.box().encrypt(url);
                hint = url.substring(url.length() - 4);
            }
        }
        String level = NotificationStore.VRAI_PROBLEME.equals(req.slackNiveau()) ? NotificationStore.VRAI_PROBLEME
                : NotificationStore.CRITIQUE;
        List<String> emails = req.emails() == null ? current.emails()
                : req.emails().stream().map(String::strip).filter(e -> !e.isEmpty()).distinct().toList();
        if (emails.size() > MAX_EMAILS || emails.stream().anyMatch(e -> !EMAIL.matcher(e).matches())) {
            return ResponseEntity.badRequest().body(Map.of("erreur", "emails"));
        }
        boolean weeklyOn = req.rapportHebdo() == null ? current.weekly() : req.rapportHebdo();
        store.save(new NotificationStore.Settings(installationId, sealed, hint, level, emails, weeklyOn), viewer.login());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/notifications/{installationId}/test-slack")
    public ResponseEntity<Map<String, String>> testSlack(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @PathVariable long installationId) {
        var account = accounts.account(viewer.admin(), viewer.userId(), installationId);
        if (account.isEmpty()) {
            return ResponseEntity.status(403).build();
        }
        var s = store.get(installationId);
        if (s.slackSealed() == null || !settings.slackEnabled()) {
            return ResponseEntity.status(409).body(Map.of("erreur", "slack-non-configure"));
        }
        if (!allowTest("slack:" + installationId)) {
            return ResponseEntity.status(429).build();
        }
        try {
            slack.post(settings.box().decrypt(s.slackSealed()), ":white_check_mark: Message de test de *PR Sentinel* pour "
                    + SlackClient.escape(account.get().compte()) + " : les alertes de sécurité arriveront ici.");
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            log.warn("test Slack en échec pour l'installation {} : {}", installationId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("erreur", "slack-echec"));
        }
    }

    /** Envoie dès maintenant le rapport des 7 derniers jours aux destinataires enregistrés. */
    @PostMapping("/api/notifications/{installationId}/test-email")
    public ResponseEntity<Map<String, String>> testEmail(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @PathVariable long installationId) {
        if (accounts.account(viewer.admin(), viewer.userId(), installationId).isEmpty()) {
            return ResponseEntity.status(403).build();
        }
        var s = store.get(installationId);
        if (!mailer.enabled() || s.emails().isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("erreur", "email-non-configure"));
        }
        if (!allowTest("email:" + installationId)) {
            return ResponseEntity.status(429).build();
        }
        try {
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            weekly.send(s, tomorrow.minusDays(7), tomorrow);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            log.warn("rapport de test en échec pour l'installation {} : {}", installationId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("erreur", "email-echec"));
        }
    }

    private boolean allowTest(String key) {
        Instant now = Instant.now();
        Instant previous = lastTest.get(key);
        if (previous != null && previous.isAfter(now.minusSeconds(60))) {
            return false;
        }
        lastTest.put(key, now);
        return true;
    }
}
