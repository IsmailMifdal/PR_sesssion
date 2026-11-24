package io.prsentinel.review;

import io.prsentinel.billing.QuotaPolicy;
import io.prsentinel.notify.ReviewNotifier;
import io.prsentinel.agent.SafeFiles;
import io.prsentinel.agent.UsageMeter;
import io.prsentinel.config.SentinelProperties;
import io.prsentinel.diff.FindingFilter;
import io.prsentinel.diff.PullRequestDiff;
import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.scan.Finding;
import io.prsentinel.scan.GitCheckout;
import io.prsentinel.scan.ScanResult;
import io.prsentinel.scan.Scanner;
import io.prsentinel.webhook.JobQueue;
import io.prsentinel.webhook.ScanJob;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Revue d'une PR : diff, clone, scanners, filtre sur les lignes modifiées, triage, publication. */
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    /** Tous les scanners ont échoué : l'infrastructure est en cause, on réessaie. */
    public static class AllScannersFailedException extends RuntimeException {
        public AllScannersFailedException(String message) {
            super(message);
        }
    }

    /** Étape de triage et de publication (agent IA, ou alertes brutes). */
    public interface Publisher {
        PublishOutcome publish(ReviewContext context);
    }

    /** Le bail a été repris par un autre worker : on ne publie rien et on n'écrit plus sur la tâche. */
    public static class LeaseLostException extends RuntimeException {
        public LeaseLostException(long jobId) {
            super("bail perdu pour la tâche " + jobId);
        }
    }

    /** Ce que voit l'étape de publication ; {@code source} n'existe que pendant l'appel. */
    public record ReviewContext(ScanJob job, String token, long reviewId, Path source, PullRequestDiff diff,
            List<ScanResult> results, List<ReviewStore.StoredFinding> findings, UsageMeter meter,
            Runnable ensureLeaseOwner, int ignoredByConfig) {}

    public record PublishOutcome(long tokensIn, long tokensOut, BigDecimal costUsd, boolean blocking) {
        public static final PublishOutcome NONE = new PublishOutcome(0, 0, BigDecimal.ZERO, false);
    }

    private final SentinelProperties props;
    private final JobQueue queue;
    private final ReviewStore store;
    private final GitHubAppClient github;
    private final GitCheckout checkout;
    private final List<Scanner> scanners;
    private final Publisher publisher;
    private final QuotaPolicy quota;
    private final String billingUrl;
    private final ReviewNotifier notifier;

    public ReviewPipeline(SentinelProperties props, JobQueue queue, ReviewStore store, GitHubAppClient github,
            GitCheckout checkout, List<Scanner> scanners, Publisher publisher) {
        this(props, queue, store, github, checkout, scanners, publisher, QuotaPolicy.UNLIMITED, null);
    }

    /** @param billingUrl page de facturation citée quand le quota est atteint ({@code null} : pas de lien) */
    public ReviewPipeline(SentinelProperties props, JobQueue queue, ReviewStore store, GitHubAppClient github,
            GitCheckout checkout, List<Scanner> scanners, Publisher publisher, QuotaPolicy quota, String billingUrl) {
        this(props, queue, store, github, checkout, scanners, publisher, quota, billingUrl, ReviewNotifier.NONE);
    }

    /** @param notifier alertes Slack après publication (jamais bloquantes pour la revue) */
    public ReviewPipeline(SentinelProperties props, JobQueue queue, ReviewStore store, GitHubAppClient github,
            GitCheckout checkout, List<Scanner> scanners, Publisher publisher, QuotaPolicy quota, String billingUrl,
            ReviewNotifier notifier) {
        this.props = props;
        this.queue = queue;
        this.store = store;
        this.github = github;
        this.checkout = checkout;
        this.scanners = List.copyOf(scanners);
        this.publisher = publisher;
        this.quota = quota;
        this.billingUrl = billingUrl;
        this.notifier = notifier;
    }

    public GitHubAppClient github() {
        return github;
    }

    public void process(ScanJob job) throws IOException, InterruptedException {
        if (queue.hasNewerJob(job)) {
            log.info("tâche {} dépassée par un push plus récent : ignorée", job.id());
            return;
        }
        if (store.alreadyPublished(job.repoFullName(), job.prNumber(), job.headSha())) {
            log.info("tâche {} : revue déjà publiée pour ce commit, rien à refaire", job.id());
            return;
        }
        QuotaPolicy.Quota q = quota.check(job.installationId());
        if (!q.allowed()) {
            skipOverQuota(job, q);
            return;
        }
        String token = github.installationToken(job.installationId(), job.repoFullName());
        PullRequestDiff diff = github.pullRequestDiff(token, job.repoFullName(), job.prNumber());
        String cloneUrl = props.github().gitUrl().replaceAll("/+$", "") + "/" + job.repoFullName() + ".git";

        Path tmp = Files.createTempDirectory("pr-sentinel-");
        try {
            Path source = checkout.checkout(cloneUrl, job.headSha(), tmp.resolve("src"), token);
            Path reports = Files.createDirectory(tmp.resolve("reports"));
            List<ScanResult> raw = scanners.stream().map(s -> runScanner(s, source, reports)).toList();
            if (!raw.isEmpty() && raw.stream().noneMatch(ScanResult::isOk)) {
                throw new AllScannersFailedException(String.join("; ",
                        raw.stream().map(r -> r.tool() + ": " + r.error()).toList()));
            }
            RepoConfig config = repoConfig(token, job);
            List<ScanResult> changed = raw.stream()
                    .map(r -> r.withFindings(FindingFilter.keepChanged(r.findings(), diff))).toList();
            List<ScanResult> results = changed.stream().map(r -> r.withFindings(config.keep(r.findings()))).toList();
            int ignored = changed.stream().mapToInt(r -> r.findings().size()).sum()
                    - results.stream().mapToInt(r -> r.findings().size()).sum();
            if (ignored > 0) {
                log.info("tâche {} : {} alerte(s) dans des chemins ignorés par {}", job.id(), ignored, RepoConfig.FILE);
            }
            List<Finding> kept = results.stream().flatMap(r -> r.findings().stream()).toList();
            log.info("tâche {} : {} alerte(s) sur les lignes modifiées (sur {})", job.id(), kept.size(),
                    raw.stream().mapToInt(r -> r.findings().size()).sum());

            if (queue.hasNewerJob(job)) {
                log.info("tâche {} dépassée pendant le scan : pas de publication", job.id());
                return;
            }
            ensureLeaseOwner(job);
            UsageMeter meter = new UsageMeter(props.agent());
            long reviewId = store.startReview(job.installationId(), job.repoFullName(), job.prNumber(), job.headSha());
            PublishOutcome outcome;
            try {
                List<ReviewStore.StoredFinding> stored = store.saveFindings(reviewId, kept,
                        kept.stream().map(f -> excerptHash(source, f)).toList());
                String fresh = github.installationToken(job.installationId(), job.repoFullName());
                outcome = publisher.publish(new ReviewContext(job, fresh, reviewId, source, diff, results, stored, meter,
                        () -> ensureLeaseOwner(job), ignored));
            } catch (RuntimeException e) {
                // Coût réel enregistré même en cas d'échec : le plafond quotidien reste juste.
                store.finish(reviewId, "ECHEC", meter.tokensIn(), meter.tokensOut(), meter.cost(), false,
                        e.getClass().getSimpleName());
                throw e;
            }
            // Hors du try : une erreur de comptabilité ne doit pas faire republier une revue déjà publiée.
            try {
                store.finish(reviewId, "TERMINEE", outcome.tokensIn(), outcome.tokensOut(), outcome.costUsd(),
                        outcome.blocking(), null);
            } catch (RuntimeException e) {
                log.error("revue {} publiée mais non clôturée en base", reviewId, e);
            }
            notifier.published(reviewId); // n'échoue jamais : une alerte manquée ne refait pas la revue
        } finally {
            deleteRecursively(tmp); // dossier de travail supprimé après chaque revue
        }
    }

    /**
     * Quota mensuel de l'offre gratuite atteint : pas d'analyse (ni clone, ni modèle), mais un check run
     * « neutral » explicite sur la PR, pour que personne ne prenne l'absence d'alerte pour un feu vert.
     */
    private void skipOverQuota(ScanJob job, QuotaPolicy.Quota q) {
        log.info("tâche {} : quota atteint pour l'installation {} ({}/{}) : PR non analysée", job.id(),
                job.installationId(), q.used(), q.limit());
        ensureLeaseOwner(job);
        String token = github.installationToken(job.installationId(), job.repoFullName());
        long reviewId = store.startReview(job.installationId(), job.repoFullName(), job.prNumber(), job.headSha());
        String summary = "Cette pull request **n'a pas été analysée** : les " + q.limit()
                + " revues mensuelles de l'offre Découverte sont utilisées. L'analyse reprendra le 1er du mois"
                + " prochain, ou dès le passage à l'offre Équipe (revues illimitées)."
                + (billingUrl == null ? "" : "\n\n[Gérer l'offre](" + billingUrl + ")");
        github.createCheckRun(token, job.repoFullName(), props.github().checkName(), job.headSha(), "neutral",
                "Non analysée : quota mensuel atteint", summary);
        store.finish(reviewId, "IGNOREE", 0, 0, BigDecimal.ZERO, false, "quota mensuel atteint");
        store.markPublished(reviewId);
    }

    /**
     * {@code .prsentinel.yml} lu au commit de BASE de la PR : la PR elle-même ne peut pas s'exclure de l'analyse.
     * Fichier absent : aucun réglage. Fichier invalide ou illisible : rien n'est ignoré (prudence).
     */
    private RepoConfig repoConfig(String token, ScanJob job) {
        if (job.baseSha() == null || !job.baseSha().matches("[0-9a-f]{40}")) {
            return RepoConfig.EMPTY;
        }
        try {
            return github.fileAt(token, job.repoFullName(), RepoConfig.FILE, job.baseSha(), RepoConfig.MAX_BYTES)
                    .map(RepoConfig::parse).orElse(RepoConfig.EMPTY);
        } catch (RuntimeException e) {
            log.warn("tâche {} : {} ignoré ({}) : toutes les alertes sont gardées", job.id(), RepoConfig.FILE,
                    e.getMessage());
            return RepoConfig.EMPTY;
        }
    }

    private void ensureLeaseOwner(ScanJob job) {
        if (!queue.extendLease(job, props.queue().lease())) {
            throw new LeaseLostException(job.id());
        }
    }

    private static ScanResult runScanner(Scanner scanner, Path source, Path reports) {
        try {
            ScanResult result = scanner.scan(source, reports);
            log.info("{} : {} alerte(s){}", result.tool(), result.findings().size(),
                    result.isOk() ? "" : ", erreur : " + result.error());
            return result;
        } catch (RuntimeException e) { // un scanner défaillant ne bloque pas les autres
            log.error("scanner {} : erreur inattendue", scanner.name(), e);
            return ScanResult.failed(scanner.name(), "erreur interne (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Empreinte SHA-256 de la ligne signalée : on garde une trace sans jamais stocker le secret. */
    static String excerptHash(Path source, Finding f) {
        String material = f.tool() + "|" + f.ruleId() + "|" + f.path();
        if (f.line() != null) {
            material += SafeFiles.resolve(source, f.path()).flatMap(SafeFiles::readLines)
                    .filter(lines -> f.line() <= lines.size())
                    .map(lines -> "|" + lines.get(f.line() - 1).strip())
                    .orElse("|" + f.line());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("nettoyage incomplet de {}", root);
        }
    }
}
