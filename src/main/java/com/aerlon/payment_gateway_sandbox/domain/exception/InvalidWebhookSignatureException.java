package com.aerlon.payment_gateway_sandbox.domain.exception;

/** Assinatura de webhook invalida. A mensagem e generica de proposito: nao ecoa corpo nem segredo. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Assinatura do webhook invalida");
    }
}
