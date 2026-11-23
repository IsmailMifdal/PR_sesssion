package io.prsentinel.review.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.support.PostgresTestBase;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Contre un vrai Tomcat : les variantes de chemin ne doivent pas contourner le jeton. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardAuthBypassTest extends PostgresTestBase {

    @LocalServerPort
    int port;

    /** Requête HTTP brute : aucun client ne « normalise » le chemin à notre place. */
    int rawGet(String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(status.substring(9, 12));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/reviews", "/api;x=1/reviews", "//api/reviews", "/api/./reviews",
            "/x/../api/reviews", "/API/reviews", "/api/reviews;x=1", "/api/%72eviews", "/api/metrics"})
    void pathVariantsNeverReachDataWithoutToken(String path) throws Exception {
        assertThat(rawGet(path)).isIn(400, 401, 404);
    }
}
