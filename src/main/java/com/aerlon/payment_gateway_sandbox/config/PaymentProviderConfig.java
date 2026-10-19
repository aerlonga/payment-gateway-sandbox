package com.aerlon.payment_gateway_sandbox.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aerlon.payment_gateway_sandbox.application.port.PaymentProvider;
import com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake.FakePaymentProvider;
import com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake.FakeProviderProperties;
import com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe.StripePaymentProvider;
import com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe.StripeProperties;

import com.stripe.StripeClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** Escolhe o provider ativo por {@code payment.provider} (fake | stripe). */
@Configuration
@EnableConfigurationProperties({ FakeProviderProperties.class, StripeProperties.class })
public class PaymentProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "payment.provider", havingValue = "fake")
    PaymentProvider fakeProvider(FakeProviderProperties properties) {
        return new FakePaymentProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.provider", havingValue = "stripe")
    PaymentProvider stripeProvider(StripeProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("payment.provider=stripe exige STRIPE_API_KEY (sk_test_...)");
        }
        StripeClient client = StripeClient.builder()
                .setApiKey(properties.apiKey())
                .setConnectTimeout(properties.connectTimeoutMs())
                .setReadTimeout(properties.readTimeoutMs())
                .setMaxNetworkRetries(properties.maxNetworkRetries())
                .build();
        return new StripePaymentProvider(client, properties, CircuitBreaker.ofDefaults("stripe"));
    }
}
