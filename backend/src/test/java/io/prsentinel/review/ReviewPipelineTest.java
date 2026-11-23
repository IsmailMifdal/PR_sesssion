package io.prsentinel.review;

import static io.prsentinel.support.LocalGit.git;
import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.agent.TriageAgent;
import io.prsentinel.config.SentinelProperties;
import io.prsentinel.support.FakeChatModel;
import java.math.BigDecimal;
import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.scan.Finding;
import io.prsentinel.scan.GitCheckout;
import io.prsentinel.scan.ScanResult;
import io.prsentinel.scan.Scanner;
import io.prsentinel.support.FakeGitHub;
import io.prsentinel.support.PostgresTestBase;
import io.prsentinel.webhook.JobQueue;
import io.prsentinel.webhook.NewJob;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Bout en bout : file PostgreSQL → worker → clone réel → scanners → filtre du diff → base → GitHub. */
class ReviewPipelineTest extends PostgresTestBase {

    /** Scanner factice : signale chaque ligne contenant {@code needle} dans le code cloné. */
    record GrepScanner(String name, String needle, List<Path> seenSources) implements Scanner {
        @Override
        public ScanResult scan(Path source, Path workdir) {
            seenSources.add(source);
            try (Stream<Path> files = Files.walk(source)) {
                List<Finding> found = new ArrayList<>();
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains(needle)) {
                            found.add(new Finding(name, "needle", "HIGH", source.relativize(p).toString(), i + 1,
                                    lines.get(i).strip()));
                        }
                    }
                }
                return ScanResult.ok(name, found);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    record BrokenScanner(String name) implements Scanner {
        @Override
        public ScanResult scan(Path source, Path workdir) {
            throw new IllegalStateException("bug");
        }
    }

    @Autowired
    JobQueue queue;
    @Autowired
    ReviewStore store;
    @Autowired
    SentinelProperties props;

    @TempDir
    Path tmp;
    FakeGitHub fake;
    Path repo;
    String sha;
    List<Path> seen = new ArrayList<>();
    ReviewWorker worker;
    ReviewPipeline pipeline;
    GitHubAppClient github;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeGitHub();
        repo = Files.createDirectories(tmp.resolve("git/acme/widgets.git"));
        git(repo, "init", "--quiet", "-b", "main");
        Files.writeString(repo.resolve("App.java"), "class App {\n  // DANGER ancien\n  void f() { run(); } // DANGER\n}\n");
        Files.writeString(repo.resolve(".gitleaksignore"), "App.java\n");
        git(repo, "add", ".");
        git(repo, "commit", "--quiet", "-m", "pr");
        sha = git(repo, "rev-parse", "HEAD");
        // La PR ne modifie que la ligne 3 : l'alerte de la ligne 2 (code existant) doit être écartée.
        fake.prFiles.put("App.java", "@@ -1,3 +1,4 @@\n class App {\n   // DANGER ancien\n+  void f() { run(); } // DANGER\n }");
        github = new GitHubAppClient(42, FakeGitHub.privateKeyPem(), fake.url(), HttpClient.newHttpClient(),
                Clock.systemUTC());
        worker = workerWith(List.of(new GrepScanner("semgrep", "DANGER", seen), new BrokenScanner("trivy")));
    }

    ReviewWorker workerWith(List<Scanner> scanners) {
        return workerWith(scanners, null, props.agent());
    }

    ReviewWorker workerWith(List<Scanner> scanners, FakeChatModel model, SentinelProperties.Agent agent) {
        var g = props.github();
        var testProps = new SentinelProperties(props.webhook(),
                new SentinelProperties.Github(42, null, fake.url(), "file://" + tmp.resolve("git"), g.checkName()),
                props.scan(), props.queue(), agent);
        ReviewPipeline.Publisher publisher = model == null ? new RawCommentPublisher(github, null)
                : new AgentReviewPublisher(testProps, github, store, new TriageAgent(model));
        pipeline = new ReviewPipeline(testProps, queue, store, github,
                new GitCheckout(Duration.ofSeconds(30), List.of("file")), scanners, publisher);
        return new ReviewWorker(queue, pipeline, testProps);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    void enqueue(String delivery, String headSha) {
        queue.enqueue(new NewJob(delivery, 99, "acme/widgets", 7, headSha, "0".repeat(40)), 3);
    }

    String jobStatus() {
        return jdbc.sql("SELECT status || '/' || attempts FROM scan_job ORDER BY id DESC LIMIT 1").query(String.class)
                .single();
    }

    @Test
    void webhookJobToRawComment() {
        enqueue("d-1", sha);
        assertThat(worker.runOnce()).isTrue();
        assertThat(worker.runOnce()).isFalse();

        assertThat(fake.comments).hasSize(1);
        String body = fake.comments.getFirst().path("body").asText();
        assertThat(body).startsWith(GitHubAppClient.MARKER).contains(sha.substring(0, 12))
                .contains("| HIGH | `needle` | `App.java:3` |").doesNotContain("App.java:2")
                .contains("| trivy | ⚠️ échec : erreur interne \\(IllegalStateException\\) |");
        assertThat(jobStatus()).isEqualTo("done/1");
        assertThat(jdbc.sql("SELECT statut FROM review").query(String.class).single()).isEqualTo("TERMINEE");
        assertThat(jdbc.sql("SELECT fichier || ':' || ligne || ':' || length(extrait_hash) FROM finding")
                .query(String.class).list()).containsExactly("App.java:3:64");
        // Overrides supprimés avant le scan, dossier de travail supprimé après.
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).doesNotExist();
    }

    @Test
    void secondPushUpdatesSameComment() throws Exception {
        enqueue("d-1", sha);
        worker.runOnce();
        Files.writeString(repo.resolve("App.java"), "class App {\n  void f() { ok(); }\n}\n");
        git(repo, "commit", "--quiet", "-am", "fix");
        String fixed = git(repo, "rev-parse", "HEAD");
        enqueue("d-2", fixed);
        worker.runOnce();
        assertThat(fake.comments).hasSize(1);
        assertThat(fake.comments.getFirst().path("body").asText()).contains(fixed.substring(0, 12))
                .doesNotContain("needle");
        assertThat(fake.requests).anyMatch(r -> r.method().equals("PATCH"));
    }

    @Test
    void transientGitHubFailureRequeuesPermanentGoesDead() {
        enqueue("d-1", sha);
        fake.failWith = 500;
        worker.runOnce();
        assertThat(jobStatus()).isEqualTo("queued/1");

        jdbc.sql("UPDATE scan_job SET run_after = now()").update();
        fake.failWith = 404;
        worker.runOnce();
        assertThat(jobStatus()).isEqualTo("dead/2");
    }

    @Test
    void unauthorizedInvalidatesCachedToken() {
        enqueue("d-1", sha);
        fake.failWith = 401;
        worker.runOnce();
        assertThat(jobStatus()).isEqualTo("queued/1");
        jdbc.sql("UPDATE scan_job SET run_after = now()").update();
        fake.failWith = null;
        int before = fake.tokenCalls.get();
        worker.runOnce();
        assertThat(fake.tokenCalls.get()).isGreaterThan(before);
        assertThat(jobStatus()).isEqualTo("done/2");
    }

    @Test
    void allScannersFailingIsRetriedWithoutReview() {
        worker = workerWith(List.of(new BrokenScanner("semgrep"), new BrokenScanner("trivy")));
        enqueue("d-1", sha);
        worker.runOnce();
        assertThat(jobStatus()).isEqualTo("queued/1");
        assertThat(jdbc.sql("SELECT last_error FROM scan_job").query(String.class).single())
                .startsWith("AllScannersFailedException");
        assertThat(jdbc.sql("SELECT count(*) FROM review").query(Integer.class).single()).isZero();
        assertThat(fake.comments).isEmpty();
    }

    @Test
    void checkoutFailureIsRetried() {
        enqueue("d-1", "f".repeat(40));
        worker.runOnce();
        assertThat(jobStatus()).isEqualTo("queued/1");
        assertThat(jdbc.sql("SELECT last_error FROM scan_job").query(String.class).single()).startsWith("CheckoutException");
    }

    @Test
    void staleJobSkippedBeforeCloning() throws Exception {
        enqueue("d-1", sha);
        var job = queue.claim(Duration.ofSeconds(60)).orElseThrow();
        enqueue("d-2", "c".repeat(40)); // push plus récent pendant que la tâche attend
        pipeline.process(job);
        assertThat(seen).isEmpty();
        assertThat(fake.comments).isEmpty();
        assertThat(fake.tokenCalls.get()).isZero();
    }

    // --- Agent IA : revue GitHub et check run ---------------------------------------------------

    SentinelProperties.Agent agent(int maxFindings, String budget) {
        var a = props.agent();
        return new SentinelProperties.Agent(true, "gpt-4o-mini", "gpt-4o", maxFindings, 5, new BigDecimal(budget),
                a.inputPricePerMillion(), a.outputPricePerMillion(), a.strongInputPricePerMillion(),
                a.strongOutputPricePerMillion(), a.timeBudget());
    }

    @Test
    void criticalFindingGetsStrongModelSecondOpinionSuggestionAndBlockingCheck() {
        FakeChatModel model = new FakeChatModel()
                .reply(FakeChatModel.verdict("VRAI_PROBLEME", "CRITIQUE", "  void f() { runSafe(); } // ok", 0.9), 1000, 100)
                .reply(FakeChatModel.verdict("VRAI_PROBLEME", "CRITIQUE", "  void f() { runSafe(); }", 0.95), 1200, 120);
        worker = workerWith(List.of(new GrepScanner("semgrep", "DANGER", seen)), model, agent(30, "1.00"));
        enqueue("d-1", sha);
        worker.runOnce();

        assertThat(model.prompts).hasSize(2);
        assertThat(model.model(0)).isEqualTo("gpt-4o-mini");
        assertThat(model.model(1)).isEqualTo("gpt-4o");
        var review = fake.reviews.getFirst();
        assertThat(review.path("commit_id").asText()).isEqualTo(sha);
        assertThat(review.path("body").asText()).contains("Faille critique détectée", "| 1 | 0 | 0 | 0 |");
        var comment = review.path("comments").get(0);
        assertThat(comment.path("path").asText()).isEqualTo("App.java");
        assertThat(comment.path("line").asInt()).isEqualTo(3);
        assertThat(comment.path("body").asText()).startsWith("**CRITIQUE**")
                .contains("```suggestion\n  void f() { runSafe(); }\n```")
                .contains("confiance 0,95", "gpt-4o");
        assertThat(fake.checkRuns.getFirst().path("conclusion").asText()).isEqualTo("failure");
        assertThat(fake.checkRuns.getFirst().path("name").asText()).isEqualTo("PR Sentinel");
        assertThat(jdbc.sql("SELECT decision || '/' || gravite || '/' || modele FROM verdict").query(String.class)
                .single()).isEqualTo("VRAI_PROBLEME/CRITIQUE/gpt-4o");
        assertThat(jdbc.sql("SELECT bloquante AND cout_usd > 0 AND tokens_entree = 2200 AND tokens_sortie = 220 FROM review")
                .query(Boolean.class).single()).isTrue();
        assertThat(fake.comments).isEmpty(); // pas de commentaire brut quand l'agent a publié
        // Le secret du dépôt n'a jamais été envoyé au modèle.
        assertThat(model.prompts.getFirst().getContents()).contains("<<<EXTRAIT DU CODE").contains("> 3:");
    }

    @Test
    void falsePositiveNotCommentedAndCheckPasses() {
        FakeChatModel model = new FakeChatModel().reply(FakeChatModel.verdict("FAUX_POSITIF", "BASSE", null, 0.9), 500, 50);
        worker = workerWith(List.of(new GrepScanner("semgrep", "DANGER", seen)), model, agent(30, "1.00"));
        enqueue("d-1", sha);
        worker.runOnce();
        var review = fake.reviews.getFirst();
        assertThat(review.path("comments")).isEmpty();
        assertThat(review.path("body").asText()).contains("Faux positifs écartés (1)", "Aucun problème de sécurité confirmé");
        assertThat(fake.checkRuns.getFirst().path("conclusion").asText()).isEqualTo("success");
    }

    @Test
    void dailyBudgetReachedFallsBackToRawAlerts() {
        FakeChatModel model = new FakeChatModel();
        worker = workerWith(List.of(new GrepScanner("semgrep", "DANGER", seen)), model, agent(30, "0.01"));
        long installation = jdbc.sql("INSERT INTO installation (github_installation_id) VALUES (99) RETURNING id")
                .query(Long.class).single();
        long repoId = jdbc.sql("INSERT INTO repository (installation_id, nom_complet) VALUES (?, 'acme/widgets') RETURNING id")
                .param(installation).query(Long.class).single();
        jdbc.sql("INSERT INTO review (repository_id, pr_numero, commit_sha, cout_usd) VALUES (?, 1, ?, 0.02)")
                .params(repoId, "d".repeat(40)).update();
        enqueue("d-1", sha);
        worker.runOnce();
        assertThat(model.prompts).isEmpty();
        assertThat(fake.reviews).isEmpty();
        assertThat(fake.comments.getFirst().path("body").asText()).contains("Budget IA quotidien atteint");
    }

    @Test
    void findingsBeyondCapAreListedUntriaged() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {\n  a(); // DANGER\n  b(); // DANGER\n}\n");
        git(repo, "commit", "--quiet", "-am", "two");
        String two = git(repo, "rev-parse", "HEAD");
        fake.prFiles.put("App.java", "@@ -1,1 +1,4 @@\n class App {\n+  a(); // DANGER\n+  b(); // DANGER\n }");
        FakeChatModel model = new FakeChatModel().reply(FakeChatModel.verdict("A_VERIFIER", "MOYENNE", null, 0.4), 10, 10);
        worker = workerWith(List.of(new GrepScanner("semgrep", "DANGER", seen)), model, agent(1, "1.00"));
        enqueue("d-1", two);
        worker.runOnce();
        assertThat(model.prompts).hasSize(1);
        var review = fake.reviews.getFirst();
        assertThat(review.path("body").asText()).contains("Alertes non triées (1", "| 0 | 1 | 0 | 1 |");
        assertThat(review.path("comments").get(0).path("body").asText()).contains("à vérifier")
                .doesNotContain("suggestion");
    }

    @Test
    void modelOutageRetriesTheJob() {
        FakeChatModel model = new FakeChatModel(); // aucune réponse : le modèle « tombe »
        worker = workerWith(List.of(new GrepScanner("semgrep", "DANGER", seen)), model, agent(30, "1.00"));
        enqueue("d-1", sha);
        worker.runOnce();
        assertThat(jobStatus()).isEqualTo("queued/1");
        assertThat(jdbc.sql("SELECT statut FROM review").query(String.class).single()).isEqualTo("ECHEC");
        assertThat(fake.reviews).isEmpty();
    }
}
