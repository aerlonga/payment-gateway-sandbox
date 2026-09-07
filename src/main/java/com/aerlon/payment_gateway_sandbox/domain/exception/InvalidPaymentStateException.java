package com.aerlon.payment_gateway_sandbox.domain.exception;

import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(PaymentStatus from, PaymentStatus to) {
        super("Transicao de status invalida: " + from + " -> " + to);
    }
}
