package demo;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Stockage dédupliqué : deux fichiers identiques partagent le même identifiant. */
public class BlobStore {
    public String contentId(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
