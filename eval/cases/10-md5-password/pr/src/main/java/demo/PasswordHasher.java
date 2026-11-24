package demo;

import java.security.MessageDigest;
import java.util.HexFormat;

public class PasswordHasher {
    public String hash(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(password.getBytes()));
    }
}
