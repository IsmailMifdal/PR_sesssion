package io.prsentinel.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.prsentinel.support.FakeGitHub;
import java.net.http.HttpClient;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubAppClientTest {

    FakeGitHub fake;
    GitHubAppClient client;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeGitHub();
        client = new GitHubAppClient(42, FakeGitHub.privateKeyPem(), fake.url(), HttpClient.newHttpClient(),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void appJwtSignedWithAppKey() throws Exception {
        SignedJWT jwt = SignedJWT.parse(client.appJwt());
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) FakeGitHub.KEYS.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("42");
        long lifetime = jwt.getJWTClaimsSet().getExpirationTime().getTime()
                - jwt.getJWTClaimsSet().getIssueTime().getTime();
        assertThat(lifetime).isLessThanOrEqualTo(600_000);
    }

    @Test
    void pkcs1KeyAccepted() throws Exception {
        // GitHub fournit la clé au format PKCS#1 (BEGIN RSA PRIVATE KEY) : on la reconstruit depuis PKCS#8.
        byte[] pkcs8 = FakeGitHub.KEYS.getPrivate().getEncoded();
        var seq = new java.io.ByteArrayInputStream(pkcs8);
        byte[] pkcs1 = extractPkcs1(pkcs8);
        String pem = "-----BEGIN RSA PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----";
        assertThat(PemKeys.readRsaPrivateKey(pem).getModulus())
                .isEqualTo(((java.security.interfaces.RSAPrivateKey) FakeGitHub.KEYS.getPrivate()).getModulus());
        assertThat(seq).isNotNull();
    }

    static String pkcs1Pem() {
        return "-----BEGIN RSA PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                        extractPkcs1(FakeGitHub.KEYS.getPrivate().getEncoded()))
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    static java.math.BigInteger modulus() {
        return ((java.security.interfaces.RSAPrivateKey) FakeGitHub.KEYS.getPrivate()).getModulus();
    }

    @Test
    void pkcs1KeyWithLostBeginLineAccepted() {
        // Cas réel : la ligne BEGIN perdue au copier-coller dans la variable d'environnement.
        String withoutBegin = pkcs1Pem().replace("-----BEGIN RSA PRIVATE KEY-----\n", "");
        assertThat(PemKeys.readRsaPrivateKey(withoutBegin).getModulus()).isEqualTo(modulus());
    }

    @Test
    void literalBackslashNAndHeaderlessKeysAccepted() {
        assertThat(PemKeys.readRsaPrivateKey(pkcs1Pem().replace("\n", "\\n")).getModulus()).isEqualTo(modulus());
        String pkcs8Body = FakeGitHub.privateKeyPem().replaceAll("-----(BEGIN|END) [A-Z ]+-----", "");
        assertThat(PemKeys.readRsaPrivateKey(pkcs8Body).getModulus()).isEqualTo(modulus());
        String pkcs1Body = pkcs1Pem().replaceAll("-----(BEGIN|END) [A-Z ]+-----", "");
        assertThat(PemKeys.readRsaPrivateKey(pkcs1Body).getModulus()).isEqualTo(modulus());
    }

    @Test
    void garbageKeyGivesClearError() {
        assertThatThrownBy(() -> PemKeys.readRsaPrivateKey("pas une clé !"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> PemKeys.readRsaPrivateKey("QUJDRA=="))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ni PKCS#1 ni PKCS#8");
    }

    /** Dernier OCTET STRING de la structure PKCS#8 = clé PKCS#1. */
    static byte[] extractPkcs1(byte[] pkcs8) {
        for (int i = pkcs8.length - 1; i > 0; i--) {
            if (pkcs8[i] == 0x04 && (pkcs8[i + 1] & 0xff) == 0x82) {
                int len = ((pkcs8[i + 2] & 0xff) << 8) | (pkcs8[i + 3] & 0xff);
                if (i + 4 + len == pkcs8.length) {
                    return java.util.Arrays.copyOfRange(pkcs8, i + 4, i + 4 + len);
                }
            }
        }
        throw new IllegalStateException("PKCS#1 introuvable");
    }

    @Test
    void installationTokenScopedAndCachedPerRepo() {
        assertThat(client.installationToken(1, "acme/widgets")).isEqualTo("ghs_inst_1");
        assertThat(client.installationToken(1, "acme/widgets")).isEqualTo("ghs_inst_1");
        assertThat(fake.tokenCalls.get()).isEqualTo(1);
        var req = fake.requests.getFirst();
        assertThat(req.authorization()).startsWith("Bearer ey");
        assertThat(req.body().path("repositories").get(0).asText()).isEqualTo("widgets");
        assertThat(req.body().path("permissions").path("contents").asText()).isEqualTo("read");
        assertThat(req.body().path("permissions").path("checks").asText()).isEqualTo("write");
        assertThat(client.installationToken(1, "acme/other")).isEqualTo("ghs_inst_2");
        client.invalidateToken(1, "acme/widgets");
        assertThat(client.installationToken(1, "acme/widgets")).isEqualTo("ghs_inst_3");
    }

    @Test
    void tokenRenewedBeforeExpiry() throws Exception {
        fake.expiresAt = "2030-01-01T01:00:00Z";
        var clock = Clock.fixed(Instant.parse("2030-01-01T00:56:00Z"), ZoneOffset.UTC);
        var c = new GitHubAppClient(42, FakeGitHub.privateKeyPem(), fake.url(), HttpClient.newHttpClient(), clock);
        c.installationToken(1, "acme/w");
        c.installationToken(1, "acme/w");
        assertThat(fake.tokenCalls.get()).isEqualTo(2); // moins de 5 min restantes : renouvelé
    }

    @Test
    void pullRequestDiffAndComments() {
        fake.prFiles.put("A.java", "@@ -1,1 +1,2 @@\n x\n+y");
        var diff = client.pullRequestDiff("t", "acme/widgets", 7);
        assertThat(diff.changedLines()).containsEntry("A.java", java.util.Set.of(2));

        assertThat(client.upsertReportComment("t", "acme/widgets", 7, GitHubAppClient.MARKER + " v1")).isEqualTo(500);
        assertThat(client.upsertReportComment("t", "acme/widgets", 7, GitHubAppClient.MARKER + " v2")).isEqualTo(500);
        assertThat(fake.comments).hasSize(1);
        assertThat(fake.comments.getFirst().path("body").asText()).endsWith("v2");
    }

    @Test
    void reviewAndCheckRun() {
        client.createReview("t", "acme/widgets", 7, "a".repeat(40), "résumé",
                List.of(new GitHubAppClient.ReviewComment("A.java", 2, "corps")));
        client.createCheckRun("t", "acme/widgets", "PR Sentinel", "a".repeat(40), false, "1 faille", "détail");
        assertThat(fake.reviews.getFirst().path("comments").get(0).path("side").asText()).isEqualTo("RIGHT");
        assertThat(fake.reviews.getFirst().path("event").asText()).isEqualTo("COMMENT");
        assertThat(fake.checkRuns.getFirst().path("conclusion").asText()).isEqualTo("failure");
    }

    @Test
    void errorsCarryStatusWithoutToken() {
        fake.failWith = 403;
        assertThatThrownBy(() -> client.upsertReportComment("ghs_secret", "acme/widgets", 7, "x"))
                .isInstanceOfSatisfying(GitHubException.class, e -> {
                    assertThat(e.isPermanent()).isTrue();
                    assertThat(e.getMessage()).contains("403").doesNotContain("ghs_secret");
                });
        assertThat(new GitHubException("x", 401).isUnauthorized()).isTrue();
        assertThat(new GitHubException("x", 500).isPermanent()).isFalse();
    }

    @Test
    void refusesUrlsOutsideApiAndParsesLinks() {
        assertThat(GitHubAppClient.nextLink("<https://api/x?page=2>; rel=\"next\", <https://api/x?page=9>; rel=\"last\""))
                .isEqualTo("https://api/x?page=2");
        assertThat(GitHubAppClient.nextLink("<https://api/x?page=9>; rel=\"last\"")).isNull();
        assertThatThrownBy(() -> new GitHubAppClient(0, FakeGitHub.privateKeyPem(), fake.url(),
                HttpClient.newHttpClient(), Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }
}
