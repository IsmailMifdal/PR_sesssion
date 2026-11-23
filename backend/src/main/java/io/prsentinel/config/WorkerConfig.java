package io.prsentinel.config;

import io.prsentinel.github.GitHubAppClient;
import io.prsentinel.review.RawCommentPublisher;
import io.prsentinel.review.ReviewPipeline;
import io.prsentinel.review.ReviewStore;
import io.prsentinel.review.ReviewWorker;
import io.prsentinel.scan.GitCheckout;
import io.prsentinel.scan.GitleaksScanner;
import io.prsentinel.scan.Scanner;
import io.prsentinel.scan.SemgrepScanner;
import io.prsentinel.scan.TrivyScanner;
import io.prsentinel.webhook.JobQueue;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage du worker. Sans GitHub App configurée, seul le rôle API (webhook) démarre. */
@Configuration(proxyBeanMethods = false)
public class WorkerConfig {

    @Bean
    @ConditionalOnExpression("${sentinel.github.app-id:0} > 0")
    GitHubAppClient gitHubAppClient(SentinelProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new GitHubAppClient(props.github().appId(), props.github().privateKey(), props.github().apiUrl(), http,
                Clock.systemUTC());
    }

    @Bean
    List<Scanner> scanners(SentinelProperties props) {
        var s = props.scan();
        return List.of(new SemgrepScanner(s.semgrepBin(), s.semgrepConfig(), s.scannerTimeout(), s.maxReportBytes()),
                new GitleaksScanner(s.gitleaksBin(), s.scannerTimeout(), s.maxReportBytes()),
                new TrivyScanner(s.trivyBin(), s.scannerTimeout(), s.maxReportBytes(), s.trivyOfflineDb()));
    }

    @Bean
    GitCheckout gitCheckout(SentinelProperties props) {
        return new GitCheckout(props.scan().cloneTimeout());
    }

    @Bean
    @ConditionalOnBean(GitHubAppClient.class)
    @ConditionalOnMissingBean(ReviewPipeline.Publisher.class)
    ReviewPipeline.Publisher rawPublisher(GitHubAppClient github) {
        return new RawCommentPublisher(github, null);
    }

    @Bean
    @ConditionalOnBean(GitHubAppClient.class)
    ReviewPipeline reviewPipeline(SentinelProperties props, JobQueue queue, ReviewStore store, GitHubAppClient github,
            GitCheckout checkout, List<Scanner> scanners, ReviewPipeline.Publisher publisher) {
        return new ReviewPipeline(props, queue, store, github, checkout, scanners, publisher);
    }

    @Bean
    @ConditionalOnBean(ReviewPipeline.class)
    @ConditionalOnProperty(prefix = "sentinel.queue", name = "worker-enabled", havingValue = "true")
    ReviewWorker reviewWorker(JobQueue queue, ReviewPipeline pipeline, SentinelProperties props) {
        return new ReviewWorker(queue, pipeline, props);
    }
}
