package io.prsentinel.review;

import io.prsentinel.agent.TriageSession;
import io.prsentinel.agent.Verdict.Decision;
import io.prsentinel.agent.Verdict.Gravite;
import io.prsentinel.scan.ScanResult;
import java.util.List;

/**
 * Conclusion du check run, fermée par défaut : la fusion est bloquée si une faille critique est confirmée,
 * mais aussi tant que la revue est incomplète sur des alertes graves (non triées, à vérifier, scanner en
 * échec). Un attaquant ne peut donc pas « passer » en saturant le plafond ou le budget.
 */
public record CheckDecision(boolean success, String title, String summary) {

    static CheckDecision of(List<AgentReviewPublisher.Item> items, List<ScanResult> results) {
        long critical = items.stream().filter(i -> i.triaged() && i.verdict().blocking()).count();
        long serious = items.stream().filter(i -> i.triaged() && i.verdict().decision() == Decision.VRAI_PROBLEME
                && i.verdict().gravite() == Gravite.HAUTE).count();
        long doubtful = items.stream().filter(i -> i.triaged() && i.verdict().decision() == Decision.A_VERIFIER
                && (i.verdict().gravite() == Gravite.CRITIQUE || i.verdict().gravite() == Gravite.HAUTE)).count();
        long untriagedSerious = items.stream()
                .filter(i -> !i.triaged() && TriageSession.isHighSeverity(i.finding().finding())).count();
        long failedScanners = results.stream().filter(r -> !r.isOk()).count();

        String summary = ReviewSummary.checkSummary(items) + (failedScanners > 0 ? " " + failedScanners
                + " scanner(s) en échec." : "");
        if (critical > 0) {
            return new CheckDecision(false, "Faille critique détectée", summary);
        }
        if (untriagedSerious > 0 || doubtful > 0 || failedScanners > 0) {
            return new CheckDecision(false, "Revue de sécurité incomplète : vérification humaine requise", summary);
        }
        long real = items.stream().filter(i -> i.triaged() && i.verdict().decision() == Decision.VRAI_PROBLEME).count();
        return new CheckDecision(true, real == 0 ? "Aucun problème de sécurité confirmé"
                : real + " problème(s) de sécurité à corriger" + (serious > 0 ? " (dont " + serious + " grave(s))" : ""),
                summary);
    }

    /** Mode sans IA (agent inactif ou budget atteint) : échec s'il reste des alertes graves ou un scanner en échec. */
    static CheckDecision raw(List<ScanResult> results, String reason) {
        long serious = results.stream().flatMap(r -> r.findings().stream()).filter(TriageSession::isHighSeverity).count();
        long failed = results.stream().filter(r -> !r.isOk()).count();
        long total = results.stream().mapToLong(r -> r.findings().size()).sum();
        String summary = reason + " " + total + " alerte(s) brute(s), dont " + serious + " grave(s) ; "
                + failed + " scanner(s) en échec.";
        return serious > 0 || failed > 0
                ? new CheckDecision(false, "Alertes graves non triées : vérification humaine requise", summary)
                : new CheckDecision(true, total == 0 ? "Aucune alerte" : "Alertes mineures uniquement", summary);
    }
}
