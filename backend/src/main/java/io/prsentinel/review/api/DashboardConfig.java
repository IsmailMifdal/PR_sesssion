package io.prsentinel.review.api;

import io.prsentinel.auth.AuthSettings;
import io.prsentinel.auth.SessionStore;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class DashboardConfig {

    @Bean
    FilterRegistrationBean<DashboardAuthFilter> dashboardAuth(@Value("${sentinel.dashboard.token:}") String token,
            AuthSettings auth, SessionStore sessions) {
        var filter = new DashboardAuthFilter(token, auth.enabled() ? sessions : null);
        FilterRegistrationBean<DashboardAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*"); // le filtre décide lui-même sur le chemin normalisé
        return reg;
    }

    /** Front hébergé ailleurs (Vercel) : origines autorisées ; lecture, facturation et échange de session. */
    @Bean
    WebMvcConfigurer dashboardCors(@Value("${sentinel.dashboard.allowed-origins:}") String origins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] allowed = Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                        .toArray(String[]::new);
                if (allowed.length > 0) {
                    registry.addMapping("/api/**").allowedOrigins(allowed).allowedMethods("GET", "POST")
                            .allowedHeaders("Authorization", "Content-Type").maxAge(3600);
                    registry.addMapping("/auth/session").allowedOrigins(allowed).allowedMethods("POST")
                            .allowedHeaders("Content-Type").maxAge(3600);
                    registry.addMapping("/auth/logout").allowedOrigins(allowed).allowedMethods("POST")
                            .allowedHeaders("Authorization").maxAge(3600);
                }
            }
        };
    }
}
