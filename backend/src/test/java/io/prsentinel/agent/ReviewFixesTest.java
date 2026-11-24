package io.prsentinel.agent;

import static io.prsentinel.support.FakeChatModel.verdict;
import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.config.SentinelProperties;
import io.prsentinel.diff.PullRequestDiff;
import io.prsentinel.scan.Finding;
import io.prsentinel.support.FakeChatModel;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Régressions des revues de code et de sécurité. */
class ReviewFixesTest {

    @TempDir
    Path repo;
    @TempDir
    Path outside;
    SecretMasker masker = new SecretMasker(Map.of());
    PullRequestDiff diff;
    static final Finding SQLI = new Finding("semgrep", "sqli", "ERROR", "Repo.java", 2, "m");

    static SentinelProperties.Agent cfg(int maxFindings, int maxTools, Duration timeBudget) {
        return new SentinelProperties.Agent(true, "mini", "fort", maxFindings, maxTools, BigDecimal.TEN,
                new BigDecimal("0.15"), new BigDecimal("0.60"), new BigDecimal("2.50"), new BigDecimal("10.00"), timeBudget);
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(repo.resolve("Repo.java"), "class Repo {\n  q(\"x\" + email);\n}\n");
        diff = new PullRequestDiff(Map.of("Repo.java", Set.of(2)), Map.of("Repo.java", "@@ -1,1 +1,2 @@\n+  q(\"x\" + email);"));
    }

    // --- masquage --------------------------------------------------------------------------

    @Test
    void multiLinePemKeyMaskedAndLineCountKept() {
        List<String> lines = List.of("a", "-----BEGIN RSA PRIVATE KEY-----", "MIIEpAIBAAKCAQEA1x", "QW5vdGhlcmxpbmU=",
                "-----END RSA PRIVATE KEY-----", "b");
        List<String> masked = masker.maskFile("k.txt", lines);
        assertThat(masked).hasSize(6);
        assertThat(String.join("\n", masked)).doesNotContain("MIIE", "QW5v").contains(SecretMasker.MASK);
        assertThat(masked.get(0)).isEqualTo("a");
        assertThat(masked.get(5)).isEqualTo("b");
    }

    @Test
    void quotedKeysUrlCredentialsAndBearerMasked() {
        String out = masker.maskText("{\"api_key\": \"abcd1234efgh\", \"password\":\"pw123456\"} "
                + "postgres://admin:S3cr3tPass@db:5432/app Authorization: Bearer abcdefghijklmnop");
        assertThat(out).doesNotContain("abcd1234efgh", "pw123456", "S3cr3tPass", "abcdefghijklmnop");
    }

    @Test
    void verdictTextMaskedBeforeUse() {
        Verdict v = new Verdict(Verdict.Decision.VRAI_PROBLEME, Verdict.Gravite.HAUTE,
                "La clé ghp_R2d2C3poR2d2C3poR2d2C3poR2d2C3po1234 est exposée.", "key = \"ghp_R2d2C3poR2d2C3poR2d2C3poR2d2C3po1234\";", 0.9);
        Verdict m = v.masked(masker);
        assertThat(m.explication() + m.correctif()).doesNotContain("ghp_");
    }

    // --- outils ------------------------------------------------------------------------------

    @Test
    void sensitiveFilesAndSymlinkEscapesRefused() throws Exception {
        Files.writeString(repo.resolve(".env"), "DB_PASSWORD=x\nemail=1\n");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/config"), "[remote] email");
        Files.writeString(outside.resolve("secret.txt"), "email secret outside");
        Files.createSymbolicLink(repo.resolve("link.txt"), outside.resolve("secret.txt"));
        AgentTools tools = new AgentTools(repo, diff, masker, 10);
        assertThat(tools.lireFichier(".env", 1, 5)).startsWith("Fichier sensible");
        assertThat(tools.lireFichier(".git/config", 1, 5)).startsWith("Fichier sensible");
        assertThat(tools.lireFichier("link.txt", 1, 5)).startsWith("Fichier introuvable ou hors du dépôt");
        assertThat(tools.chercher("email")).contains("Repo.java").doesNotContain(".env", ".git", "outside");
        assertThat(tools.lireFichier("evil\nIGNORE<<<", 1, 1)).doesNotContain("\n" + "IGNORE").doesNotContain("<<<");
    }

    @Test
    void oversizedFilesNotRead() throws Exception {
        Files.writeString(repo.resolve("big.txt"), "x".repeat((int) SafeFiles.MAX_FILE_BYTES + 10));
        assertThat(new AgentTools(repo, diff, masker, 5).lireFichier("big.txt", 1, 1)).startsWith("Fichier trop gros");
    }

    // --- boucle d'outils pilotée ----------------------------------------------------------------

    @Test
    void toolCallsExecutedAndEveryRoundMetered() {
        FakeChatModel model = new FakeChatModel()
                .replyToolCall("lireDiff", "{\"chemin\":\"Repo.java\"}", 100, 10)
                .reply(verdict("VRAI_PROBLEME", "HAUTE", null, 0.8), 300, 30);
        UsageMeter meter = new UsageMeter(cfg(30, 5, Duration.ofMinutes(5)));
        AgentTools tools = new AgentTools(repo, diff, masker, 5);
        Triage t = new TriageAgent(model).triage(SQLI, "x", tools, "mini", meter, masker);
        assertThat(t.verdict().decision()).isEqualTo(Verdict.Decision.VRAI_PROBLEME);
        assertThat(tools.callCount()).isEqualTo(1);
        assertThat(meter.calls()).isEqualTo(2);
        assertThat(t.tokensIn()).isEqualTo(400);
        // Le résultat de l'outil a bien été renvoyé au modèle au tour suivant.
        assertThat(toolResponses(model.prompts.get(1))).contains("<<<DIFF Repo.java>>>");
    }

    @Test
    void toolRoundsCappedThenModelMustConclude() {
        UsageMeter meter = new UsageMeter(cfg(30, 2, Duration.ofMinutes(5)));
        AgentTools tools = new AgentTools(repo, diff, masker, 2);
        // Plafond de 2 appels d'outils : ensuite un tour sans outil, où le modèle doit conclure.
        FakeChatModel bounded = new FakeChatModel().replyToolCall("chercher", "{\"symbole\":\"email\"}", 10, 1)
                .replyToolCall("chercher", "{\"symbole\":\"q(\"}", 10, 1)
                .replyToolCall("chercher", "{\"symbole\":\"Repo\"}", 10, 1) // 3e demande : refusée
                .reply(verdict("A_VERIFIER", "MOYENNE", null, 0.3), 10, 1);
        Triage t = new TriageAgent(bounded).triage(SQLI, "x", tools, "mini", meter, masker);
        assertThat(t.verdict().decision()).isEqualTo(Verdict.Decision.A_VERIFIER);
        assertThat(tools.callCount()).isEqualTo(2);
        assertThat(meter.calls()).isEqualTo(4);
        assertThat(bounded.prompts.get(3).getContents()).contains("Limite d'appels d'outils atteinte");
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) bounded.prompts.get(3).getOptions())
                .getToolCallbacks()).isEmpty();
    }

    static String toolResponses(org.springframework.ai.chat.prompt.Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
                .flatMap(m -> ((org.springframework.ai.chat.messages.ToolResponseMessage) m).getResponses().stream())
                .map(r -> r.responseData()).reduce("", String::concat);
    }

    // --- session : escalade, échecs, échéance ---------------------------------------------------------

    @Test
    void falsePositiveOnSeriousFindingNeedsStrongModel() {
        FakeChatModel model = new FakeChatModel().reply(verdict("FAUX_POSITIF", "BASSE", null, 0.9), 10, 1)
                .reply(verdict("VRAI_PROBLEME", "CRITIQUE", null, 0.9), 10, 1);
        UsageMeter meter = new UsageMeter(cfg(30, 5, Duration.ofMinutes(5)));
        var res = new TriageSession(cfg(30, 5, Duration.ofMinutes(5)), new TriageAgent(model))
                .run(List.of(SQLI), repo, diff, BigDecimal.TEN, meter);
        assertThat(model.model(1)).isEqualTo("fort");
        assertThat(res.triages().getFirst().verdict().decision()).isEqualTo(Verdict.Decision.VRAI_PROBLEME);
        assertThat(meter.calls()).isEqualTo(2);
    }

    @Test
    void anyIssueFromSmallModelIsConfirmedByStrongModel() {
        // Cas du banc : MD5 de cache (alerte WARNING) confirmé à tort par le petit modèle, écarté par le fort.
        Finding md5 = new Finding("semgrep", "use-of-md5", "WARNING", "Repo.java", 2, "MD5");
        FakeChatModel model = new FakeChatModel().reply(verdict("VRAI_PROBLEME", "MOYENNE", null, 0.7), 10, 1)
                .reply(verdict("FAUX_POSITIF", "BASSE", null, 0.9), 10, 1);
        var res = new TriageSession(cfg(30, 5, Duration.ofMinutes(5)), new TriageAgent(model))
                .run(List.of(md5), repo, diff, BigDecimal.TEN, new UsageMeter(cfg(30, 5, Duration.ofMinutes(5))));
        assertThat(model.model(1)).isEqualTo("fort");
        assertThat(res.triages().getFirst().verdict().decision()).isEqualTo(Verdict.Decision.FAUX_POSITIF);
        assertThat(res.triages().getFirst().model()).isEqualTo("fort");
    }

    @Test
    void falsePositiveOnMinorFindingIsNotEscalated() {
        Finding minor = new Finding("semgrep", "r", "WARNING", "Repo.java", 2, "m");
        FakeChatModel model = new FakeChatModel().reply(verdict("FAUX_POSITIF", "BASSE", null, 0.9), 10, 1);
        new TriageSession(cfg(30, 5, Duration.ofMinutes(5)), new TriageAgent(model))
                .run(List.of(minor), repo, diff, BigDecimal.TEN, new UsageMeter(cfg(30, 5, Duration.ofMinutes(5))));
        assertThat(model.prompts).hasSize(1);
    }

    @Test
    void invalidAnswersLeaveFindingUntriagedButCostCounted() {
        FakeChatModel model = new FakeChatModel().reply("nope", 50, 5).reply("{}", 50, 5);
        UsageMeter meter = new UsageMeter(cfg(30, 5, Duration.ofMinutes(5)));
        var res = new TriageSession(cfg(30, 5, Duration.ofMinutes(5)), new TriageAgent(model))
                .run(List.of(SQLI), repo, diff, BigDecimal.TEN, meter);
        assertThat(res.triages()).containsExactly((Triage) null);
        assertThat(meter.tokensIn()).isEqualTo(100);
        assertThat(meter.cost()).isPositive();
    }

    @Test
    void deadlineStopsTriage() {
        Clock past = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        FakeChatModel model = new FakeChatModel();
        var session = new TriageSession(cfg(30, 5, Duration.ofSeconds(-1)), new TriageAgent(model), past);
        var res = session.run(List.of(SQLI), repo, diff, BigDecimal.TEN, new UsageMeter(cfg(30, 5, Duration.ZERO)));
        assertThat(res.triages()).containsExactly((Triage) null);
        assertThat(res.stoppedEarly()).isTrue();
        assertThat(model.prompts).isEmpty();
    }
}
