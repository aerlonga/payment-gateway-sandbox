package com.aerlon.payment_gateway_sandbox.application.port;

public record RefundCommand(
        String providerPaymentId,
        long amount,
        String reason) {
}
