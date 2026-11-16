package com.aerlon.payment_gateway_sandbox.infrastructure.provider.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidWebhookSignatureException;
import com.stripe.net.Webhook;

class StripeWebhookVerifierTest {

    private static final String SECRET = "test-only-signing-secret";

    private final StripeWebhookVerifier verifier = new StripeWebhookVerifier(
            new StripeProperties("sk_test_x", "", 1000, 1000, 0, SECRET));

    private static String payload(String eventId, String type, String objectId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type
                + "\",\"data\":{\"object\":{\"id\":\"" + objectId + "\",\"object\":\"payment_intent\"}}}";
    }

    private static String sign(String payload, String secret, long timestamp) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, secret, timestamp);
    }

    private static String sign(String payload) throws Exception {
        return sign(payload, SECRET, Webhook.Util.getTimeNow());
    }

    @Test
    void validSucceededEventMapsToCaptured() throws Exception {
        String body = payload("evt_1", "payment_intent.succeeded", "pi_test_1");

        ProviderEvent event = verifier.verify(body, sign(body));

        assertThat(event).isEqualTo(new ProviderEvent("evt_1", "payment_intent.succeeded", "pi_test_1",
                PaymentStatus.CAPTURED));
    }

    @Test
    void paymentFailedMapsToFailed() throws Exception {
        String body = payload("evt_2", "payment_intent.payment_failed", "pi_test_2");
        assertThat(verifier.verify(body, sign(body)).newStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void canceledMapsToCanceled() throws Exception {
        String body = payload("evt_3", "payment_intent.canceled", "pi_test_3");
        assertThat(verifier.verify(body, sign(body)).newStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void unsupportedTypeReturnsNullNewStatus() throws Exception {
        String body = payload("evt_4", "customer.created", "cus_1");

        ProviderEvent event = verifier.verify(body, sign(body));

        assertThat(event.newStatus()).isNull();
        assertThat(event.eventId()).isEqualTo("evt_4");
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        String body = payload("evt_5", "payment_intent.succeeded", "pi_test_5");
        String header = sign(body, "another-test-only-secret", Webhook.Util.getTimeNow());
        assertThatThrownBy(() -> verifier.verify(body, header)).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        String body = payload("evt_6", "payment_intent.succeeded", "pi_test_6");
        String header = sign(body);
        String tampered = body.replace("pi_test_6", "pi_other");
        assertThatThrownBy(() -> verifier.verify(tampered, header)).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void missingHeaderIsRejected() {
        String body = payload("evt_7", "payment_intent.succeeded", "pi_test_7");
        assertThatThrownBy(() -> verifier.verify(body, null)).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void oldTimestampIsRejected() throws Exception {
        String body = payload("evt_8", "payment_intent.succeeded", "pi_test_8");
        String header = sign(body, SECRET, Webhook.Util.getTimeNow() - 600);
        assertThatThrownBy(() -> verifier.verify(body, header)).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void exceptionMessageDoesNotLeakPayloadOrSecret() throws Exception {
        String body = payload("evt_9", "payment_intent.succeeded", "pi_test_9");
        String header = sign(body, "another-test-only-secret", Webhook.Util.getTimeNow());

        assertThatThrownBy(() -> verifier.verify(body, header))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .message().doesNotContain("pi_test_9").doesNotContain("evt_9").doesNotContain(SECRET);
    }
}
