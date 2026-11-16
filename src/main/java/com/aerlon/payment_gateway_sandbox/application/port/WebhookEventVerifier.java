package com.aerlon.payment_gateway_sandbox.application.port;

import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidWebhookSignatureException;

/** Porta: valida a assinatura de um webhook do provider e o traduz para um {@link ProviderEvent}. */
public interface WebhookEventVerifier {

    /**
     * @throws InvalidWebhookSignatureException se a assinatura for invalida, ausente, expirada
     *                                          ou o corpo nao for um evento legivel
     */
    ProviderEvent verify(String payload, String signatureHeader);
}
