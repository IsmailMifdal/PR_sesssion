package io.prsentinel.notify;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Rapport hebdomadaire par e-mail (texte brut), envoyé le lundi pour la semaine précédente. Chaque envoi est
 * réservé en base : une seule instance l'envoie, une seule fois, même si l'API et le worker exécutent la tâche.
 */
public class WeeklyReport {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReport.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH);

    private final NotificationStore store;
    private final Mailer mailer;
    private final String frontUrl;
    private final Clock clock;

    public WeeklyReport(NotificationStore store, Mailer mailer, String frontUrl, Clock clock) {
        this.store = store;
        this.mailer = mailer;
        this.frontUrl = frontUrl == null || frontUrl.isBlank() ? null : frontUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    @Scheduled(cron = "${sentinel.notify.weekly-cron:0 13 7 * * MON}", zone = "UTC")
    public void run() {
        if (!mailer.enabled()) {
            return;
        }
        LocalDate thisMonday = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (var s : store.weeklyRecipients()) {
            if (!store.claimWeekly(s.githubInstallationId(), thisMonday)) {
                continue; // déjà envoyé (autre instance ou exécution précédente)
            }
            try {
                send(s, thisMonday.minusWeeks(1), thisMonday);
            } catch (RuntimeException e) {
                log.warn("rapport hebdomadaire non envoyé pour l'installation {} : {}", s.githubInstallationId(),
                        e.getMessage());
            }
        }
    }

    /** Envoie le rapport de la période [from, to[ aux destinataires du compte (utilisé aussi pour l'envoi de test). */
    public void send(NotificationStore.Settings s, LocalDate from, LocalDate to) {
        var w = store.week(s.githubInstallationId(), from, to);
        mailer.send(s.emails(), subject(w, from), body(w, from, to));
        log.info("rapport hebdomadaire envoyé pour l'installation {} ({} destinataire(s))", s.githubInstallationId(),
                s.emails().size());
    }

    static String subject(NotificationStore.Week w, LocalDate from) {
        return "PR Sentinel, semaine du " + from.format(DAY) + " : " + w.issues() + " faille" + (w.issues() > 1 ? "s" : "")
                + ", " + w.dismissed() + " fausse" + (w.dismissed() > 1 ? "s" : "") + " alerte"
                + (w.dismissed() > 1 ? "s" : "") + " écartée" + (w.dismissed() > 1 ? "s" : "");
    }

    String body(NotificationStore.Week w, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bonjour,\n\nVoici l'activité de PR Sentinel du ").append(from.format(DAY)).append(" au ")
                .append(to.minusDays(1).format(DAY)).append(".\n\n");
        sb.append("Pull requests analysées : ").append(w.reviews()).append('\n');
        sb.append("Revues bloquantes (faille critique) : ").append(w.blocking()).append('\n');
        sb.append("Vrais problèmes : ").append(w.issues());
        if (w.issues() > 0) {
            sb.append(" (critiques ").append(w.critical()).append(", hautes ").append(w.high()).append(", moyennes ")
                    .append(w.medium()).append(", basses ").append(w.low()).append(')');
        }
        sb.append('\n');
        sb.append("Fausses alertes écartées par l'agent : ").append(w.dismissed()).append('\n');
        sb.append(String.format(Locale.FRENCH, "Coût de l'analyse : %.4f $%n", w.costUsd()));
        if (!w.topRepos().isEmpty()) {
            sb.append("\nDépôts avec le plus de vrais problèmes :\n");
            w.topRepos().forEach(r -> sb.append("  - ").append(r).append('\n'));
        }
        if (w.reviews() == 0) {
            sb.append("\nAucune pull request analysée cette semaine.\n");
        }
        if (frontUrl != null) {
            sb.append("\nConsole : ").append(frontUrl).append("/console\n");
            sb.append("Gérer ces e-mails : ").append(frontUrl).append("/console/notifications\n");
        }
        sb.append("\nPR Sentinel\n");
        return sb.toString();
    }
}
