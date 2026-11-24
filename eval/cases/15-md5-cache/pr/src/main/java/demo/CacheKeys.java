package demo;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Clé de cache déterministe pour des réponses HTTP publiques (aucun usage de sécurité). */
public class CacheKeys {
    public String keyFor(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return "http-cache:" + HexFormat.of().formatHex(md.digest(url.getBytes()));
    }
}
