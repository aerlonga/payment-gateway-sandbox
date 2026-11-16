package com.aerlon.payment_gateway_sandbox.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.aerlon.payment_gateway_sandbox.PostgresTestConfiguration;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.application.port.WebhookEventPublisher;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.stripe.net.Webhook;

/** Contrato HTTP do webhook com contexto real e publisher mockado (sem Kafka). */
@SpringBootTest(properties = "payment.stripe.webhook-secret=test-only-signing-secret")
@Import(PostgresTestConfiguration.class)
class StripeWebhookControllerTest {

    private static final String URL = "/api/v1/webhooks/stripe";
    private static final String SECRET = "test-only-signing-secret";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private WebhookEventPublisher publisher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Mockito.reset(publisher);
    }

    private static String payload(String eventId, String type, String objectId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type
                + "\",\"data\":{\"object\":{\"id\":\"" + objectId + "\",\"object\":\"payment_intent\"}}}";
    }

    private static String sign(String body) throws Exception {
        return Webhook.Signature.generateSignatureHeader(body, SECRET, Webhook.Util.getTimeNow());
    }

    @Test
    void validSignedEventReturns200AndPublishes() throws Exception {
        String body = payload("evt_c1", "payment_intent.succeeded", "pi_c1");

        mvc.perform(post(URL).header("Stripe-Signature", sign(body)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(publisher).publish(new ProviderEvent("evt_c1", "payment_intent.succeeded", "pi_c1",
                PaymentStatus.CAPTURED));
    }

    @Test
    void ignoredTypeReturns200AndDoesNotPublish() throws Exception {
        String body = payload("evt_c2", "customer.created", "cus_c2");

        mvc.perform(post(URL).header("Stripe-Signature", sign(body)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(publisher, never()).publish(any());
    }

    @Test
    void invalidSignatureReturns400AndDoesNotPublish() throws Exception {
        String body = payload("evt_c3", "payment_intent.succeeded", "pi_c3");

        mvc.perform(post(URL).header("Stripe-Signature", "t=1,v1=deadbeef").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(publisher, never()).publish(any());
    }

    @Test
    void missingSignatureHeaderReturns400() throws Exception {
        String body = payload("evt_c4", "payment_intent.succeeded", "pi_c4");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(publisher, never()).publish(any());
    }
}
