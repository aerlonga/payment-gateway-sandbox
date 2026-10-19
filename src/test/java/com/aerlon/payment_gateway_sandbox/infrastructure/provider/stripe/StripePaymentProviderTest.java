package com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aerlon.payment_gateway_sandbox.application.port.CreatePaymentCommand;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderPayment;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderRefund;
import com.aerlon.payment_gateway_sandbox.application.port.RefundCommand;
import com.aerlon.payment_gateway_sandbox.config.PaymentProviderConfig;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentProviderException;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.service.PaymentIntentService;
import com.stripe.service.RefundService;

class StripePaymentProviderTest {

    private static final CreatePaymentCommand COMMAND =
            new CreatePaymentCommand(UUID.randomUUID(), UUID.randomUUID(), 15000, "BRL", "key-1", null, null);

    private StripeClient client;
    private PaymentIntentService intents;
    private RefundService refunds;

    @BeforeEach
    void setUp() {
        client = mock(StripeClient.class);
        intents = mock(PaymentIntentService.class);
        refunds = mock(RefundService.class);
        when(client.paymentIntents()).thenReturn(intents);
        when(client.refunds()).thenReturn(refunds);
    }

    private StripePaymentProvider provider(String paymentMethod) {
        return new StripePaymentProvider(client, new StripeProperties("sk_test_x", paymentMethod, 1000, 1000, 0),
                PaymentProviderConfig.stripeCircuitBreaker());
    }

    private static PaymentIntent intent(String id, String status) {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(id);
        when(intent.getStatus()).thenReturn(status);
        when(intent.getClientSecret()).thenReturn(id + "_secret");
        return intent;
    }

    @Test
    void mapsStripeStatusesToDomain() {
        assertThat(StripePaymentProvider.toPaymentStatus("succeeded")).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(StripePaymentProvider.toPaymentStatus("requires_capture")).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(StripePaymentProvider.toPaymentStatus("canceled")).isEqualTo(PaymentStatus.CANCELED);
        assertThat(StripePaymentProvider.toPaymentStatus("requires_payment_method")).isEqualTo(PaymentStatus.PENDING);
        assertThat(StripePaymentProvider.toPaymentStatus("processing")).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void createWithoutPaymentMethodStaysPendingAndSendsIdempotencyKey() throws Exception {
        PaymentIntent created = intent("pi_1", "requires_payment_method");
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(created);

        ProviderPayment result = provider("").createPayment(COMMAND);

        assertThat(result.providerPaymentId()).isEqualTo("pi_1");
        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.clientSecret()).isEqualTo("pi_1_secret");

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        org.mockito.Mockito.verify(intents).create(params.capture(), options.capture());
        assertThat(params.getValue().getAmount()).isEqualTo(15000L);
        assertThat(params.getValue().getCurrency()).isEqualTo("brl");
        assertThat(params.getValue().getConfirm()).isNull();
        assertThat(options.getValue().getIdempotencyKey()).endsWith(":key-1");
    }

    @Test
    void createWithPaymentMethodConfirmsAndCaptures() throws Exception {
        PaymentIntent created = intent("pi_2", "succeeded");
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(created);

        ProviderPayment result = provider("pm_card_visa").createPayment(COMMAND);

        assertThat(result.status()).isEqualTo(PaymentStatus.CAPTURED);
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        org.mockito.Mockito.verify(intents).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getConfirm()).isTrue();
        assertThat(params.getValue().getPaymentMethod()).isEqualTo("pm_card_visa");
    }

    @Test
    void networkFailureBecomesProviderException() throws Exception {
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("sem rede"));

        assertThatThrownBy(() -> provider("").createPayment(COMMAND))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void refundReturnsStripeRefundData() throws Exception {
        Refund refund = mock(Refund.class);
        when(refund.getId()).thenReturn("re_1");
        when(refund.getAmount()).thenReturn(5000L);
        when(refunds.create(any(com.stripe.param.RefundCreateParams.class))).thenReturn(refund);

        ProviderRefund result = provider("").refund(new RefundCommand("pi_1", 5000, "requested_by_customer"));

        assertThat(result.providerRefundId()).isEqualTo("re_1");
        assertThat(result.amount()).isEqualTo(5000);
    }

    @Test
    void cardDeclineBecomesFailedAndDoesNotCountAsBreakerFailure() throws Exception {
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new CardException("recusado", "req_1", "card_declined", null, "card_declined", null, 402, null));
        var breaker = PaymentProviderConfig.stripeCircuitBreaker();
        var provider = new StripePaymentProvider(client,
                new StripeProperties("sk_test_x", "", 1000, 1000, 0), breaker);

        assertThat(provider.createPayment(COMMAND).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void idempotencyKeyIsScopedByCustomer() throws Exception {
        PaymentIntent created = intent("pi_3", "requires_payment_method");
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(created);

        provider("").createPayment(COMMAND);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        org.mockito.Mockito.verify(intents).create(any(PaymentIntentCreateParams.class), options.capture());
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo(COMMAND.customerId() + ":key-1");
    }

    @Test
    void sendsDescriptionAndExplicitPaymentMethodTypeInsteadOfAutomatic() throws Exception {
        PaymentIntent created = intent("pi_4", "requires_payment_method");
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(created);
        CreatePaymentCommand command = new CreatePaymentCommand(
                UUID.randomUUID(), UUID.randomUUID(), 15000, "BRL", "key-2", "card", "Pedido #1234");

        provider("").createPayment(command);

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        org.mockito.Mockito.verify(intents).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getDescription()).isEqualTo("Pedido #1234");
        assertThat(params.getValue().getPaymentMethodTypes()).containsExactly("card");
        assertThat(params.getValue().getAutomaticPaymentMethods()).isNull();
    }

    @Test
    void usesAutomaticPaymentMethodsWhenNoTypeIsRequested() throws Exception {
        PaymentIntent created = intent("pi_5", "requires_payment_method");
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(created);

        provider("").createPayment(COMMAND);

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        org.mockito.Mockito.verify(intents).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getAutomaticPaymentMethods()).isNotNull();
        assertThat(params.getValue().getPaymentMethodTypes()).isNullOrEmpty();
        assertThat(params.getValue().getDescription()).isNull();
    }
}
