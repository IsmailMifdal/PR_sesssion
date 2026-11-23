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

    static RSAPrivateKey readRsaPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("SENTINEL_GITHUB_PRIVATE_KEY est requis");
        }
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            byte[] pkcs8 = pkcs1 ? wrapPkcs1(der) : der;
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("clé privée RSA illisible", e);
        }
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
