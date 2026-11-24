package io.prsentinel.config;

import io.prsentinel.agent.TriageAgent;
import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.review.AgentReviewPublisher;
import io.prsentinel.review.RawCommentPublisher;
import io.prsentinel.review.ReviewPipeline;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.review.ReviewWorker;
import io.prsentinel.scan.GitCheckout;
import io.prsentinel.scan.GitleaksScanner;
import io.prsentinel.scan.ScannerSet;
import io.prsentinel.scan.SemgrepScanner;
import io.prsentinel.scan.TrivyScanner;
import io.prsentinel.webhook.JobQueue;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Rôle worker : actif seulement si la GitHub App est configurée ET le worker activé. Une seule condition,
 * posée sur la classe, plutôt qu'une chaîne de {@code @ConditionalOnBean} sensible à l'ordre des beans.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${sentinel.github.app-id:0} > 0 and ${sentinel.queue.worker-enabled:true}")
public class WorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfig.class);
    static final String NO_KEY = "non-configuree";

    @Bean
    GitHubAppClient gitHubAppClient(SentinelProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new GitHubAppClient(props.github().appId(), props.github().privateKey(), props.github().apiUrl(), http,
                Clock.systemUTC());
    }

    @Bean
    ScannerSet scanners(SentinelProperties props) {
        var s = props.scan();
        return new ScannerSet(List.of(
                new SemgrepScanner(s.semgrepBin(), s.semgrepConfig(), s.scannerTimeout(), s.maxReportBytes()),
                new GitleaksScanner(s.gitleaksBin(), s.scannerTimeout(), s.maxReportBytes()),
                new TrivyScanner(s.trivyBin(), s.scannerTimeout(), s.maxReportBytes(), s.trivyOfflineDb())));
    }

    @Bean
    GitCheckout gitCheckout(SentinelProperties props) {
        return new GitCheckout(props.scan().cloneTimeout());
    }

    /** Agent IA si une clé est configurée, sinon alertes brutes (comportement de la semaine 1). */
    @Bean
    ReviewPipeline.Publisher publisher(SentinelProperties props, GitHubAppClient github, ReviewStore store,
            ObjectProvider<ChatModel> chatModel, Environment env) {
        ChatModel model = chatModel.getIfAvailable();
        String key = env.getProperty("spring.ai.openai.api-key", NO_KEY);
        boolean keyConfigured = !key.isBlank() && !NO_KEY.equals(key); // une clé vide vaut absence de clé
        if (props.agent().enabled() && model != null && keyConfigured) {
            log.info("agent IA actif : {} (modèle fort {})", props.agent().model(), props.agent().strongModel());
            return new AgentReviewPublisher(props, github, store, new TriageAgent(model));
        }
        log.warn("agent IA inactif (clé absente ou sentinel.agent.enabled=false) : alertes brutes");
        return new RawCommentPublisher(github, store, props.github().checkName(), null);
    }

    @Bean
    ReviewPipeline reviewPipeline(SentinelProperties props, JobQueue queue, ReviewStore store, GitHubAppClient github,
            GitCheckout checkout, ScannerSet scanners, ReviewPipeline.Publisher publisher) {
        return new ReviewPipeline(props, queue, store, github, checkout, scanners.scanners(), publisher);
    }

    @Bean
    ReviewWorker reviewWorker(JobQueue queue, ReviewPipeline pipeline, ReviewStore store, SentinelProperties props) {
        return new ReviewWorker(queue, pipeline, store, props);
    }
}
