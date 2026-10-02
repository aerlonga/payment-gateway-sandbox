package com.aerlon.payment_gateway_sandbox.domain.enums;

public enum PaymentEventType {
    CREATED("payment.created"),
    AUTHORIZED("payment.authorized"),
    CONFIRMED("payment.confirmed"),
    FAILED("payment.failed"),
    CANCELED("payment.canceled"),
    REFUNDED("payment.refunded");

    private final String value;

    PaymentEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Evento correspondente a uma transicao para o status informado. */
    public static PaymentEventType forStatus(PaymentStatus status) {
        return switch (status) {
            case PENDING -> CREATED;
            case AUTHORIZED -> AUTHORIZED;
            case CAPTURED -> CONFIRMED;
            case FAILED -> FAILED;
            case CANCELED -> CANCELED;
            case REFUNDED -> REFUNDED;
        };
    }
}
