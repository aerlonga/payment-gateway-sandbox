package com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey          chave secreta do sandbox (sk_test_...), vem de STRIPE_API_KEY
 * @param paymentMethod   opcional: payment method de teste (ex.: pm_card_visa). Preenchido, o
 *                        PaymentIntent e confirmado na criacao (vira CAPTURED); vazio, fica
 *                        PENDING ate a confirmacao pelo front/webhook
 * @param connectTimeoutMs timeout de conexao com a API do Stripe
 * @param readTimeoutMs    timeout de leitura
 * @param maxNetworkRetries retries do SDK em falha de rede (seguros: o create usa Idempotency-Key)
 */
@ConfigurationProperties(prefix = "payment.stripe")
public record StripeProperties(
        String apiKey,
        String paymentMethod,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxNetworkRetries) {
}
