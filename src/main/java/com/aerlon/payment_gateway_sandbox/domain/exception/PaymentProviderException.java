package com.aerlon.payment_gateway_sandbox.domain.exception;

/** Falha de comunicacao ou erro inesperado no gateway externo. */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
