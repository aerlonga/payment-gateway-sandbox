package com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param latencyMsMin  latencia minima simulada por chamada
 * @param latencyMsMax  latencia maxima simulada por chamada
 * @param failureRate   fracao (0..1) das cobrancas recusadas, para exercitar o caminho de erro
 */
@ConfigurationProperties(prefix = "payment.fake")
public record FakeProviderProperties(
        long latencyMsMin,
        long latencyMsMax,
        double failureRate) {

    public FakeProviderProperties {
        if (latencyMsMin < 0 || latencyMsMax < latencyMsMin) {
            throw new IllegalArgumentException("payment.fake: latency-ms-min/max invalidos");
        }
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("payment.fake.failure-rate deve estar entre 0 e 1");
        }
    }
}
