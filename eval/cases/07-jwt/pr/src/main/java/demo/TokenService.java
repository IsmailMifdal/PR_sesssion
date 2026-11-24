package demo;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;

public class TokenService {
    public String currentUser(String bearer) {
        DecodedJWT jwt = JWT.decode(bearer);
        return jwt.getSubject();
    }
}
