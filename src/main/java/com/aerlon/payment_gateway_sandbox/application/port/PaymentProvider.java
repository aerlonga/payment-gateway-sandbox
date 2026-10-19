package com.aerlon.payment_gateway_sandbox.application.port;

/**
 * Porta para o gateway de pagamento externo. O dominio e os casos de uso
 * dependem so desta interface; cada gateway (Stripe, fake, ...) e um adapter
 * na camada de infraestrutura.
 */
public interface PaymentProvider {

    ProviderPayment createPayment(CreatePaymentCommand command);

    ProviderPayment getPayment(String providerPaymentId);

    ProviderRefund refund(RefundCommand command);

    void cancel(String providerPaymentId);

    /** Nome gravado em {@code payments.provider}, ex.: "stripe", "fake". */
    String name();
}
