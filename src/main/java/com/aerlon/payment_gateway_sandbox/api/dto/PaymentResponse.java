package com.aerlon.payment_gateway_sandbox.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representacao de um pagamento")
public record PaymentResponse(
        UUID id,
        UUID customerId,
        Long amount,
        String currency,
        @Schema(description = "Total ja estornado, em centavos") Long refundedAmount,
        PaymentStatus status,
        String provider,
        String providerPaymentId,
        String paymentMethod,
        String description,
        Instant createdAt,
        Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Segredo do PaymentIntent para o front confirmar o pagamento. So vem na criacao (201) "
                + "ou no replay de um pagamento ainda PENDING; nunca e gravado nem logado")
        String clientSecret) {

    public PaymentResponse withClientSecret(String secret) {
        return new PaymentResponse(id, customerId, amount, currency, refundedAmount, status, provider,
                providerPaymentId, paymentMethod, description, createdAt, updatedAt, secret);
    }
}
