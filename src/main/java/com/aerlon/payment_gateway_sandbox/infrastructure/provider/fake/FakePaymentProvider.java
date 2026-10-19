package com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.aerlon.payment_gateway_sandbox.application.port.CreatePaymentCommand;
import com.aerlon.payment_gateway_sandbox.application.port.PaymentProvider;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderPayment;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderRefund;
import com.aerlon.payment_gateway_sandbox.application.port.RefundCommand;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentProviderException;

import lombok.extern.slf4j.Slf4j;

/**
 * Provider simulado para testes de carga e automatizados. Nao faz rede: responde
 * localmente depois de uma latencia aleatoria e recusa uma fracao configuravel
 * das cobrancas. Cobranca aprovada ja nasce CAPTURED (nao ha webhook aqui).
 */
@Slf4j
public class FakePaymentProvider implements PaymentProvider {

    public static final String NAME = "fake";

    private final FakeProviderProperties properties;

    public FakePaymentProvider(FakeProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProviderPayment createPayment(CreatePaymentCommand command) {
        simulateLatency();
        String id = "pi_fake_" + UUID.randomUUID().toString().replace("-", "");
        if (ThreadLocalRandom.current().nextDouble() < properties.failureRate()) {
            log.debug("Fake provider recusou payment={}", command.paymentId());
            return new ProviderPayment(id, PaymentStatus.FAILED, null);
        }
        return new ProviderPayment(id, PaymentStatus.CAPTURED, id + "_secret_fake");
    }

    @Override
    public ProviderPayment getPayment(String providerPaymentId) {
        simulateLatency();
        return new ProviderPayment(providerPaymentId, PaymentStatus.CAPTURED, null);
    }

    @Override
    public ProviderRefund refund(RefundCommand command) {
        simulateLatency();
        return new ProviderRefund("re_fake_" + UUID.randomUUID().toString().replace("-", ""), command.amount());
    }

    @Override
    public void cancel(String providerPaymentId) {
        simulateLatency();
    }

    @Override
    public String name() {
        return NAME;
    }

    private void simulateLatency() {
        long min = properties.latencyMsMin();
        long max = properties.latencyMsMax();
        long delay = (max > min) ? ThreadLocalRandom.current().nextLong(min, max + 1) : min;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentProviderException("Fake provider interrompido", e);
        }
    }
}
