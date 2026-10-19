package com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe;

import java.util.Locale;

import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import com.aerlon.payment_gateway_sandbox.application.port.CreatePaymentCommand;
import com.aerlon.payment_gateway_sandbox.application.port.PaymentProvider;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderPayment;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderRefund;
import com.aerlon.payment_gateway_sandbox.application.port.RefundCommand;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentProviderException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

/** Adapter do Stripe (sandbox) atras da porta {@link PaymentProvider}, protegido por circuit breaker. */
@Slf4j
public class StripePaymentProvider implements PaymentProvider {

    public static final String NAME = "stripe";

    private final StripeClient client;
    private final StripeProperties properties;
    private final CircuitBreaker circuitBreaker;

    public StripePaymentProvider(StripeClient client, StripeProperties properties, CircuitBreaker circuitBreaker) {
        this.client = client;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ProviderPayment createPayment(CreatePaymentCommand command) {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(command.amount())
                .setCurrency(command.currency().toLowerCase(Locale.ROOT))
                .putMetadata("paymentId", command.paymentId().toString())
                .putMetadata("customerId", command.customerId().toString());

        if (hasText(properties.paymentMethod())) {
            params.setPaymentMethod(properties.paymentMethod())
                    .setConfirm(true)
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                            .build());
        } else {
            params.setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .build());
        }

        RequestOptions options = RequestOptions.builder().setIdempotencyKey(command.idempotencyKey()).build();
        try {
            PaymentIntent intent = call(() -> client.paymentIntents().create(params.build(), options));
            return toProviderPayment(intent);
        } catch (CardException e) {
            // Recusa do cartao e resultado de negocio, nao falha de infraestrutura.
            log.info("Stripe recusou payment={} code={}", command.paymentId(), e.getCode());
            String id = (e.getStripeError() != null && e.getStripeError().getPaymentIntent() != null)
                    ? e.getStripeError().getPaymentIntent().getId()
                    : null;
            return new ProviderPayment(id, PaymentStatus.FAILED, null);
        }
    }

    @Override
    public ProviderPayment getPayment(String providerPaymentId) {
        try {
            return toProviderPayment(call(() -> client.paymentIntents().retrieve(providerPaymentId)));
        } catch (CardException e) {
            throw new PaymentProviderException("Erro ao consultar pagamento no Stripe", e);
        }
    }

    @Override
    public ProviderRefund refund(RefundCommand command) {
        RefundCreateParams.Builder params = RefundCreateParams.builder()
                .setPaymentIntent(command.providerPaymentId())
                .setAmount(command.amount());
        if (hasText(command.reason())) {
            params.putMetadata("reason", command.reason());
            toStripeReason(command.reason()).ifPresent(params::setReason);
        }
        try {
            Refund refund = call(() -> client.refunds().create(params.build()));
            return new ProviderRefund(refund.getId(), refund.getAmount());
        } catch (CardException e) {
            throw new PaymentProviderException("Erro ao criar estorno no Stripe", e);
        }
    }

    @Override
    public void cancel(String providerPaymentId) {
        try {
            call(() -> client.paymentIntents().cancel(providerPaymentId));
        } catch (CardException e) {
            throw new PaymentProviderException("Erro ao cancelar pagamento no Stripe", e);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Mapeia o status do PaymentIntent para o status do dominio. */
    static PaymentStatus toPaymentStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> PaymentStatus.CAPTURED;
            case "requires_capture" -> PaymentStatus.AUTHORIZED;
            case "canceled" -> PaymentStatus.CANCELED;
            default -> PaymentStatus.PENDING; // requires_payment_method / confirmation / action / processing
        };
    }

    private static ProviderPayment toProviderPayment(PaymentIntent intent) {
        return new ProviderPayment(intent.getId(), toPaymentStatus(intent.getStatus()), intent.getClientSecret());
    }

    private static java.util.Optional<RefundCreateParams.Reason> toStripeReason(String reason) {
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "requested_by_customer" -> java.util.Optional.of(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
            case "duplicate" -> java.util.Optional.of(RefundCreateParams.Reason.DUPLICATE);
            case "fraudulent" -> java.util.Optional.of(RefundCreateParams.Reason.FRAUDULENT);
            default -> java.util.Optional.empty();
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Executa a chamada ao Stripe dentro do circuit breaker. CardException passa direto
     * (nao conta como falha do Stripe); qualquer outro erro vira PaymentProviderException.
     */
    private <T> T call(StripeCall<T> call) throws CardException {
        try {
            return circuitBreaker.executeCallable(call::run);
        } catch (CardException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            throw new PaymentProviderException("Stripe indisponivel (circuit breaker aberto)", e);
        } catch (StripeException e) {
            throw new PaymentProviderException("Erro na API do Stripe: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PaymentProviderException("Falha inesperada ao chamar o Stripe", e);
        }
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }
}
