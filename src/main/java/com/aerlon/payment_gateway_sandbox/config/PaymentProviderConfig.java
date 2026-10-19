package com.aerlon.payment_gateway_sandbox.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aerlon.payment_gateway_sandbox.application.port.PaymentProvider;
import com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake.FakePaymentProvider;
import com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake.FakeProviderProperties;

/** Escolhe o provider ativo por {@code payment.provider} (fake | stripe). */
@Configuration
@EnableConfigurationProperties(FakeProviderProperties.class)
public class PaymentProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "payment.provider", havingValue = "fake")
    PaymentProvider fakeProvider(FakeProviderProperties properties) {
        return new FakePaymentProvider(properties);
    }
}
