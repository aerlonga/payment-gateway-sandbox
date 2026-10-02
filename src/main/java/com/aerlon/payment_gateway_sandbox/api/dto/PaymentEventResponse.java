package com.aerlon.payment_gateway_sandbox.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.aerlon.payment_gateway_sandbox.domain.enums.EventPublishStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Evento do historico de um pagamento")
public record PaymentEventResponse(
        UUID id,
        UUID paymentId,
        @Schema(example = "payment.created") String eventType,
        Map<String, Object> payload,
        EventPublishStatus status,
        Instant createdAt) {
}
