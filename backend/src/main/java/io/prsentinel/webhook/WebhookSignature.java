package io.prsentinel.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Vérification de {@code X-Hub-Signature-256} (HMAC-SHA256 sur le corps brut). */
public final class WebhookSignature {

    public static final String PREFIX = "sha256=";

    private WebhookSignature() {}

    public static String sign(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /** Comparaison à temps constant ({@link MessageDigest#isEqual}). */
    public static boolean verify(byte[] secret, byte[] body, String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.US_ASCII));
    }
}
