package io.prsentinel.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.prsentinel.review.ReviewWorker;
import io.prsentinel.support.FakeGitHub;
import io.prsentinel.support.PostgresTestBase;
import io.prsentinel.webhook.WebhookController;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Les rôles API et worker se câblent selon la configuration, sans dépendre de l'ordre des beans. */
class WorkerWiringTest {

    @Nested
    class ApiRole extends PostgresTestBase {
        @Autowired
        ApplicationContext ctx;

        @Test
        void noWorkerWithoutGitHubApp() {
            assertThat(ctx.getBeanNamesForType(ReviewWorker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(WebhookController.class)).hasSize(1);
        }
    }

    /** Contexte fermé après la classe : son worker ne doit pas réserver les tâches des autres tests. */
    @Nested
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {"sentinel.github.app-id=42", "sentinel.queue.worker-enabled=true",
            "sentinel.queue.poll-interval=1h", "sentinel.webhook.enabled=false", "sentinel.webhook.secret="})
    class WorkerRole extends PostgresTestBase {
        @Autowired
        ApplicationContext ctx;

        @DynamicPropertySource
        static void key(DynamicPropertyRegistry registry) {
            registry.add("sentinel.github.private-key", FakeGitHub::privateKeyPem);
        }

        @Test
        void workerWiredAndWebhookDisabledWithoutSecret() {
            assertThat(ctx.getBean(ReviewWorker.class).isRunning()).isTrue();
            assertThat(ctx.getBeanNamesForType(WebhookController.class)).isEmpty();
        }
    }
}
