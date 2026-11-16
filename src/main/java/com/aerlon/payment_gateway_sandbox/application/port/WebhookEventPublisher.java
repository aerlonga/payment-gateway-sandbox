package com.aerlon.payment_gateway_sandbox.application.port;

/** Porta: enfileira um evento do provider para processamento assincrono. */
public interface WebhookEventPublisher {

    /** Retorna so depois do ack do broker; lanca excecao se a publicacao falhar. */
    void publish(ProviderEvent event);
}
