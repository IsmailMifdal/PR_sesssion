package io.prsentinel.review.api;

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
    FilterRegistrationBean<DashboardAuthFilter> dashboardAuth(@Value("${sentinel.dashboard.token:}") String token) {
        FilterRegistrationBean<DashboardAuthFilter> reg = new FilterRegistrationBean<>(new DashboardAuthFilter(token));
        reg.addUrlPatterns("/*"); // le filtre décide lui-même sur le chemin normalisé
        return reg;
    }

    /** Front hébergé ailleurs (Vercel) : origines autorisées, lecture seule. */
    @Bean
    WebMvcConfigurer dashboardCors(@Value("${sentinel.dashboard.allowed-origins:}") String origins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] allowed = Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                        .toArray(String[]::new);
                if (allowed.length > 0) {
                    registry.addMapping("/api/**").allowedOrigins(allowed).allowedMethods("GET")
                            .allowedHeaders("Authorization").maxAge(3600);
                }
            }
        };
    }
}
