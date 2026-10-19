package com.aerlon.payment_gateway_sandbox.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;
import com.aerlon.payment_gateway_sandbox.api.dto.RefundRequest;
import com.aerlon.payment_gateway_sandbox.application.mapper.PaymentMapper;
import com.aerlon.payment_gateway_sandbox.application.port.PaymentProvider;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderRefund;
import com.aerlon.payment_gateway_sandbox.application.port.RefundCommand;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.entities.PaymentEvent;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidPaymentStateException;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidRefundException;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentEventRepository;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentRepository;

class PaymentServiceRefundTest {

    private PaymentRepository paymentRepository;
    private PaymentEventRepository eventRepository;
    private PaymentProvider provider;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        eventRepository = mock(PaymentEventRepository.class);
        provider = mock(PaymentProvider.class);
        when(provider.refund(any())).thenAnswer(inv -> {
            RefundCommand command = inv.getArgument(0);
            return new ProviderRefund("re_test", command.amount());
        });
        service = new PaymentService(paymentRepository, eventRepository, new PaymentMapper(), provider);
    }

    private Payment captured(long amount, long refunded) {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .amount(amount)
                .currency("BRL")
                .status(PaymentStatus.CAPTURED)
                .provider("fake")
                .providerPaymentId("pi_fake_1")
                .refundedAmount(refunded)
                .idempotencyKey("k")
                .build();
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void partialRefundKeepsCapturedAndAccumulates() {
        Payment payment = captured(19990, 0);

        PaymentResponse response = service.refund(payment.getId(), new RefundRequest(5000L, "teste"));

        assertThat(response.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(response.refundedAmount()).isEqualTo(5000L);
        ArgumentCaptor<RefundCommand> command = ArgumentCaptor.forClass(RefundCommand.class);
        verify(provider).refund(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(5000L);

        ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("payment.refunded");
        assertThat(event.getValue().getPayload()).containsEntry("remaining", 14990L);
    }

    @Test
    void refundingTheRemainderMarksRefunded() {
        Payment payment = captured(19990, 5000);

        PaymentResponse response = service.refund(payment.getId(), new RefundRequest(null, null));

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(response.refundedAmount()).isEqualTo(19990L);
        ArgumentCaptor<RefundCommand> command = ArgumentCaptor.forClass(RefundCommand.class);
        verify(provider).refund(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(14990L);
    }

    @Test
    void rejectsAmountAboveRemainingBalance() {
        Payment payment = captured(10000, 6000);

        assertThatThrownBy(() -> service.refund(payment.getId(), new RefundRequest(4001L, null)))
                .isInstanceOf(InvalidRefundException.class);
        verify(provider, never()).refund(any());
    }

    @Test
    void rejectsRefundWhenNotCaptured() {
        Payment payment = captured(10000, 0);
        payment.setStatus(PaymentStatus.REFUNDED);

        assertThatThrownBy(() -> service.refund(payment.getId(), new RefundRequest(100L, null)))
                .isInstanceOf(InvalidPaymentStateException.class);
        verify(provider, never()).refund(any());
    }
}
