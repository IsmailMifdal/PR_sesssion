package io.prsentinel.github;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Lecture de la clé privée de la GitHub App (PEM PKCS#1, format fourni par GitHub, ou PKCS#8). */
final class PemKeys {

    private PemKeys() {}

    /**
     * Accepte une clé PKCS#1 ({@code RSA PRIVATE KEY}, format fourni par GitHub) ou PKCS#8, y compris quand
     * une ligne d'en-tête a été perdue au copier-coller ou que les retours à la ligne sont des {@code \\n}
     * littéraux (fréquent dans les variables d'environnement).
     */
    static RSAPrivateKey readRsaPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("SENTINEL_GITHUB_PRIVATE_KEY est requis");
        }
        String text = pem.replace("\\n", "\n");
        boolean pkcs1Hint = text.contains("RSA PRIVATE KEY");
        String base64 = text.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("clé privée RSA illisible : contenu base64 invalide", e);
        }
        byte[][] attempts = pkcs1Hint ? new byte[][] {wrapPkcs1(der), der} : new byte[][] {der, wrapPkcs1(der)};
        GeneralSecurityException last = null;
        for (byte[] candidate : attempts) {
            try {
                return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(candidate));
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw new IllegalStateException("clé privée RSA illisible (ni PKCS#1 ni PKCS#8) : vérifier le contenu du .pem", last);
    }

    /** Enveloppe une clé PKCS#1 dans la structure PKCS#8 (PrivateKeyInfo, algorithme rsaEncryption). */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01,
                0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = derTlv((byte) 0x04, pkcs1);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algorithm);
        inner.writeBytes(octet);
        return derTlv((byte) 0x30, inner.toByteArray());
    }

    private static byte[] derTlv(byte tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xff) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xffff) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
