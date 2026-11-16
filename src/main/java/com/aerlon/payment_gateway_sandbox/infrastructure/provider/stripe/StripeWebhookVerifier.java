package com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.application.port.WebhookEventVerifier;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidWebhookSignatureException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Valida o header Stripe-Signature (HMAC-SHA256 + tolerancia de 300 s) e extrai o minimo
 * necessario do evento. Le o JSON direto, sem desserializar o modelo do SDK, para nao
 * depender da versao de API da conta ser igual a do SDK. So existe com o segredo configurado.
 */
@Component
@ConditionalOnExpression("!'${payment.stripe.webhook-secret:}'.isBlank()")
public class StripeWebhookVerifier implements WebhookEventVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String webhookSecret;

    public StripeWebhookVerifier(StripeProperties properties) {
        this.webhookSecret = properties.webhookSecret();
    }

    @Override
    public ProviderEvent verify(String payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException();
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, webhookSecret, Webhook.DEFAULT_TOLERANCE);
            JsonNode event = MAPPER.readTree(payload);
            String eventId = required(event.path("id"));
            String type = required(event.path("type"));
            String objectId = required(event.path("data").path("object").path("id"));
            return new ProviderEvent(eventId, type, objectId, statusFor(type));
        } catch (SignatureVerificationException | RuntimeException e) {
            // RuntimeException cobre JSON malformado / campos ausentes; a mensagem original nunca sobe
            throw new InvalidWebhookSignatureException();
        }
    }

    private static String required(JsonNode node) {
        String value = node.asString("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("campo obrigatorio ausente");
        }
        return value;
    }

    private static PaymentStatus statusFor(String type) {
        return switch (type) {
            case "payment_intent.succeeded" -> PaymentStatus.CAPTURED;
            case "payment_intent.payment_failed" -> PaymentStatus.FAILED;
            case "payment_intent.canceled" -> PaymentStatus.CANCELED;
            default -> null;
        };
    }
}
