package com.aerlon.payment_gateway_sandbox.application.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.aerlon.payment_gateway_sandbox.api.dto.CreatePaymentRequest;
import com.aerlon.payment_gateway_sandbox.application.mapper.PaymentMapper;
import com.aerlon.payment_gateway_sandbox.application.port.CreatePaymentCommand;
import com.aerlon.payment_gateway_sandbox.application.port.PaymentProvider;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderPayment;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.IdempotencyConflictException;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentProviderException;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentEventRepository;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentRepository;

class PaymentServiceCreateTest {

    private static final UUID CUSTOMER = UUID.randomUUID();

    private PaymentRepository paymentRepository;
    private PaymentProvider provider;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        provider = mock(PaymentProvider.class);
        when(provider.name()).thenReturn("fake");
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment payment = inv.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });
        service = new PaymentService(paymentRepository, mock(PaymentEventRepository.class), new PaymentMapper(),
                provider, mock(PlatformTransactionManager.class));
    }

    private static CreatePaymentRequest request(String paymentMethod, String description) {
        return new CreatePaymentRequest(CUSTOMER, 15000L, "BRL", paymentMethod, description);
    }

    private Payment existing(PaymentStatus status, String paymentMethod, String description) {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID()).customerId(CUSTOMER).amount(15000L).currency("BRL")
                .status(status).provider("stripe").providerPaymentId("pi_1")
                .paymentMethod(paymentMethod).description(description).idempotencyKey("k").build();
        when(paymentRepository.findByIdempotencyKey("k")).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void createReturnsClientSecretAndPersistsRequestFields() {
        when(provider.createPayment(any())).thenReturn(new ProviderPayment("pi_1", PaymentStatus.PENDING, "pi_1_secret_x"));

        CreatePaymentResult result = service.create(request("card", "Pedido #1234"), "k");

        assertThat(result.created()).isTrue();
        assertThat(result.payment().clientSecret()).isEqualTo("pi_1_secret_x");
        assertThat(result.payment().paymentMethod()).isEqualTo("card");
        assertThat(result.payment().description()).isEqualTo("Pedido #1234");
        assertThat(result.payment().providerPaymentId()).isEqualTo("pi_1");

        ArgumentCaptor<CreatePaymentCommand> command = ArgumentCaptor.forClass(CreatePaymentCommand.class);
        verify(provider).createPayment(command.capture());
        assertThat(command.getValue().paymentMethod()).isEqualTo("card");
        assertThat(command.getValue().description()).isEqualTo("Pedido #1234");
    }

    @Test
    void replayOfPendingPaymentFetchesClientSecretFromProvider() {
        existing(PaymentStatus.PENDING, "card", "Pedido #1234");
        when(provider.getPayment("pi_1")).thenReturn(new ProviderPayment("pi_1", PaymentStatus.PENDING, "pi_1_secret_y"));

        CreatePaymentResult result = service.create(request("card", "Pedido #1234"), "k");

        assertThat(result.created()).isFalse();
        assertThat(result.payment().clientSecret()).isEqualTo("pi_1_secret_y");
        verify(provider, never()).createPayment(any());
    }

    @Test
    void replayOfCapturedPaymentDoesNotCallProviderNorReturnSecret() {
        existing(PaymentStatus.CAPTURED, null, null);

        CreatePaymentResult result = service.create(request(null, null), "k");

        assertThat(result.created()).isFalse();
        assertThat(result.payment().clientSecret()).isNull();
        verify(provider, never()).getPayment(any());
    }

    @Test
    void replayStillSucceedsWhenProviderFailsToReturnSecret() {
        existing(PaymentStatus.PENDING, null, null);
        when(provider.getPayment("pi_1")).thenThrow(new PaymentProviderException("fora do ar"));

        CreatePaymentResult result = service.create(request(null, null), "k");

        assertThat(result.created()).isFalse();
        assertThat(result.payment().clientSecret()).isNull();
    }

    @Test
    void sameKeyWithDifferentDescriptionOrMethodConflicts() {
        existing(PaymentStatus.CAPTURED, "card", "Pedido #1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(request("card", "Pedido #2"), "k"))
                .isInstanceOf(IdempotencyConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(request("pix", "Pedido #1"), "k"))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
