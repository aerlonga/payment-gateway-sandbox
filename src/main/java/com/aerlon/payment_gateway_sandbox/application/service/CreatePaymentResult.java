package com.aerlon.payment_gateway_sandbox.application.service;

import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;

/**
 * Resultado de uma tentativa de criacao: diz se o pagamento nasceu agora (201)
 * ou se foi um replay idempotente do mesmo payload (200).
 */
public record CreatePaymentResult(PaymentResponse payment, boolean created) {

    static CreatePaymentResult created(PaymentResponse payment) {
        return new CreatePaymentResult(payment, true);
    }

    static CreatePaymentResult replayed(PaymentResponse payment) {
        return new CreatePaymentResult(payment, false);
    }
}
