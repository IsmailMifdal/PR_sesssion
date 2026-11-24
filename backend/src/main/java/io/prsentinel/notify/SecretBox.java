package io.prsentinel.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement des secrets des clients stockés en base (URL de webhook Slack) : AES-256-GCM, nonce aléatoire de
 * 96 bits préfixé au texte chiffré. La clé ({@code SENTINEL_ENCRYPTION_KEY}, 32 octets en base64) ne vit que dans
 * l'environnement : une fuite de la base seule ne révèle pas les webhooks.
 */
public final class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    private SecretBox(byte[] key) {
        this.key = new SecretKeySpec(key, "AES");
    }

    /** @return {@code null} si la clé est absente ou invalide : les fonctions qui en dépendent sont désactivées. */
    public static SecretBox fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(base64Key.strip());
            return key.length == 32 ? new SecretBox(key) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("chiffrement impossible", e);
        }
    }

    /** Déchiffre ; une valeur altérée (ou chiffrée avec une autre clé) est refusée par le tag GCM. */
    public String decrypt(String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            return new String(c.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("secret illisible (clé changée ou donnée altérée)");
        }
    }
}
