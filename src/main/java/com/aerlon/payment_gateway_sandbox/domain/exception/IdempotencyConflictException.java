package com.aerlon.payment_gateway_sandbox.domain.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key ja usada com payload diferente: " + idempotencyKey);
    }
}
