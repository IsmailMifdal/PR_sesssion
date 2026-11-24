package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class S3ClientTest {
    // Clé factice pour le bouchon S3 local (aucun compte réel).
    private static final String FAKE_ACCESS_KEY = "@@FAKE_ACCESS_KEY@@";
    private static final String FAKE_SECRET = "@@FAKE_SECRET@@";

    @Test
    void signsRequestsWithConfiguredKey() {
        S3Client client = new S3Client("http://localhost:4566", FAKE_ACCESS_KEY, FAKE_SECRET);
        assertEquals("http://localhost:4566", client.endpoint());
    }
}
