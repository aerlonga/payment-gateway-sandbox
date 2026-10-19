package com.aerlon.payment_gateway_sandbox.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representacao de um pagamento")
public record PaymentResponse(
        UUID id,
        UUID customerId,
        Long amount,
        String currency,
        PaymentStatus status,
        String provider,
        String providerPaymentId,
        Instant createdAt,
        Instant updatedAt) {
}
