package io.prsentinel.auth;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration(proxyBeanMethods = false)
public class AuthConfig {

    @Bean
    AuthSettings authSettings(@Value("${sentinel.auth.github-client-id:}") String clientId,
            @Value("${sentinel.auth.github-client-secret:}") String clientSecret,
            @Value("${sentinel.auth.public-url:}") String publicUrl,
            @Value("${sentinel.auth.front-url:}") String frontUrl,
            @Value("${sentinel.auth.oauth-url:${sentinel.github.git-url}}") String oauthUrl,
            @Value("${sentinel.github.api-url}") String apiUrl,
            @Value("${sentinel.auth.session-ttl:8h}") Duration sessionTtl) {
        return new AuthSettings(clientId, clientSecret, publicUrl, frontUrl, oauthUrl, apiUrl, sessionTtl);
    }

    @Bean
    GitHubOAuthClient gitHubOAuthClient(AuthSettings settings) {
        return new GitHubOAuthClient(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** La connexion GitHub n'est exposée que si l'app OAuth est entièrement configurée. */
    public static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return new AuthSettings(env.getProperty("sentinel.auth.github-client-id"),
                    env.getProperty("sentinel.auth.github-client-secret"), env.getProperty("sentinel.auth.public-url"),
                    env.getProperty("sentinel.auth.front-url"), null, null, null).enabled();
        }
    }
}
