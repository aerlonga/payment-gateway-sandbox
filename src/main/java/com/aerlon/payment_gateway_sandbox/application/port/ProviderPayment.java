package com.aerlon.payment_gateway_sandbox.application.port;

import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

/** Resultado de uma cobranca no provider. {@code status} e o estado resultante do pagamento. */
public record ProviderPayment(
        String providerPaymentId,
        PaymentStatus status,
        String clientSecret) {
}
