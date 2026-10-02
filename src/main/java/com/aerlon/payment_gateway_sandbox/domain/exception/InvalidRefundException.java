package com.aerlon.payment_gateway_sandbox.domain.exception;

public class InvalidRefundException extends RuntimeException {

    public InvalidRefundException(String message) {
        super(message);
    }
}
