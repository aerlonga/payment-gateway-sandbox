package com.aerlon.payment_gateway_sandbox.api.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.application.port.WebhookEventPublisher;
import com.aerlon.payment_gateway_sandbox.application.port.WebhookEventVerifier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Recebe webhooks do Stripe: valida a assinatura, enfileira no Kafka e responde 200 sem
 * processar. So existe quando STRIPE_WEBHOOK_SECRET esta configurado. Nao loga corpo nem header.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@ConditionalOnExpression("!'${payment.stripe.webhook-secret:}'.isBlank()")
@Tag(name = "Webhooks", description = "Eventos assincronos do provider de pagamento")
public class StripeWebhookController {

    private final WebhookEventVerifier verifier;
    private final WebhookEventPublisher publisher;

    @PostMapping(value = "/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Recebe um evento do Stripe (autenticado pelo header Stripe-Signature)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evento aceito (enfileirado) ou ignorado por tipo"),
            @ApiResponse(responseCode = "400", description = "Assinatura ausente, invalida ou expirada") })
    public ResponseEntity<Void> receive(@RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        ProviderEvent event = verifier.verify(new String(payload, StandardCharsets.UTF_8), signature);
        if (event.newStatus() != null) {
            publisher.publish(event);
        }
        return ResponseEntity.ok().build();
    }
}
