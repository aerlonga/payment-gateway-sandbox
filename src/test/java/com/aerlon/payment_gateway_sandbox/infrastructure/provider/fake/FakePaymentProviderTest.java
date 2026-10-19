package com.aerlon.payment_gateway_sandbox.infrastructure.provider.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aerlon.payment_gateway_sandbox.application.port.CreatePaymentCommand;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderPayment;
import com.aerlon.payment_gateway_sandbox.application.port.RefundCommand;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

class FakePaymentProviderTest {

    private static final CreatePaymentCommand COMMAND =
            new CreatePaymentCommand(UUID.randomUUID(), UUID.randomUUID(), 15000, "BRL", "key-1", null, null);

    @Test
    void approvesAndCapturesWhenFailureRateIsZero() {
        var provider = new FakePaymentProvider(new FakeProviderProperties(0, 0, 0));

        ProviderPayment payment = provider.createPayment(COMMAND);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.providerPaymentId()).startsWith("pi_fake_");
        assertThat(payment.clientSecret()).isNotBlank();
        assertThat(provider.name()).isEqualTo("fake");
    }

    @Test
    void declinesWhenFailureRateIsOne() {
        var provider = new FakePaymentProvider(new FakeProviderProperties(0, 0, 1));

        assertThat(provider.createPayment(COMMAND).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void appliesSimulatedLatency() {
        var provider = new FakePaymentProvider(new FakeProviderProperties(50, 60, 0));

        long start = System.nanoTime();
        provider.createPayment(COMMAND);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }

    @Test
    void refundEchoesAmount() {
        var provider = new FakePaymentProvider(new FakeProviderProperties(0, 0, 0));

        var refund = provider.refund(new RefundCommand("pi_fake_x", 5000, "teste"));

        assertThat(refund.amount()).isEqualTo(5000);
        assertThat(refund.providerRefundId()).startsWith("re_fake_");
    }

    @Test
    void rejectsInvalidProperties() {
        assertThatThrownBy(() -> new FakeProviderProperties(10, 5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeProviderProperties(0, 0, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
