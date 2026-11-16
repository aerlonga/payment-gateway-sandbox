package com.aerlon.payment_gateway_sandbox.application.port;

import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

/**
 * Evento neutro vindo do provider (webhook). {@code newStatus == null} indica um tipo de
 * evento que a aplicacao ignora.
 */
public record ProviderEvent(String eventId, String type, String providerPaymentId, PaymentStatus newStatus) {
}
