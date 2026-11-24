package io.prsentinel.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base des tests d'intégration : PostgreSQL réel.
 *
 * <p>Testcontainers si Docker est disponible (CI), sinon la base désignée par {@code TEST_DATABASE_URL}
 * (format JDBC), par exemple dans un environnement de développement sans Docker.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer<?> CONTAINER = startContainerIfPossible();

    @Autowired
    protected JdbcClient jdbc;

    private static PostgreSQLContainer<?> startContainerIfPossible() {
        if (System.getenv("TEST_DATABASE_URL") != null || !DockerClientFactory.instance().isDockerAvailable()) {
            return null;
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (CONTAINER != null) {
            registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
            registry.add("spring.datasource.username", CONTAINER::getUsername);
            registry.add("spring.datasource.password", CONTAINER::getPassword);
        } else {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null) {
                throw new IllegalStateException("Docker indisponible : définir TEST_DATABASE_URL (jdbc:postgresql://...)");
            }
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> envOr("TEST_DATABASE_USER", "sentinel"));
            registry.add("spring.datasource.password", () -> envOr("TEST_DATABASE_PASSWORD", "sentinel"));
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE installation, repository, review, finding, verdict, scan_job, app_user, subscription, verdict_feedback, notification_settings, weekly_report_sent RESTART IDENTITY CASCADE")
                .update();
    }
}
