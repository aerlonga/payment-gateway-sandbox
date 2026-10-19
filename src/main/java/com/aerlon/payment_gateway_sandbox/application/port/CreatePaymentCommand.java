package com.aerlon.payment_gateway_sandbox.application.port;

import java.util.UUID;

public record CreatePaymentCommand(
        UUID paymentId,
        UUID customerId,
        long amount,
        String currency,
        String idempotencyKey,
        String paymentMethod,
        String description) {
}
