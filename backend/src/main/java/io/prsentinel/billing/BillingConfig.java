package io.prsentinel.billing;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BillingConfig {

    @Bean
    BillingSettings billingSettings(@Value("${sentinel.billing.stripe-secret-key:}") String secretKey,
            @Value("${sentinel.billing.stripe-webhook-secret:}") String webhookSecret,
            @Value("${sentinel.billing.stripe-price-equipe:}") String priceEquipe,
            @Value("${sentinel.billing.stripe-api-url:https://api.stripe.com}") String stripeApi,
            @Value("${sentinel.auth.front-url:}") String frontUrl,
            @Value("${sentinel.billing.free-reviews-per-month:50}") int freeReviews,
            @Value("${sentinel.billing.trial-days:14}") int trialDays,
            @Value("${sentinel.billing.quotas-enabled:false}") boolean quotasEnabled) {
        return new BillingSettings(secretKey, webhookSecret, priceEquipe, stripeApi, frontUrl, freeReviews, trialDays,
                quotasEnabled);
    }

    @Bean
    StripeClient stripeClient(BillingSettings settings) {
        return new StripeClient(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    @Bean
    QuotaPolicy quotaPolicy(BillingSettings settings, SubscriptionStore store) {
        return settings.quotasEnabled() ? QuotaPolicy.monthly(store, settings.freeReviewsPerMonth()) : QuotaPolicy.UNLIMITED;
    }
}
