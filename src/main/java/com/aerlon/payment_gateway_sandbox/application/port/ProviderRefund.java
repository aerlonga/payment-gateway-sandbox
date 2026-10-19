package com.aerlon.payment_gateway_sandbox.application.port;

public record ProviderRefund(
        String providerRefundId,
        long amount) {
}
