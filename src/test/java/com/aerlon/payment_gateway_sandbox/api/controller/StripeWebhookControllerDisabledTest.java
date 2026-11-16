package com.aerlon.payment_gateway_sandbox.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.aerlon.payment_gateway_sandbox.PostgresTestConfiguration;

/** Sem STRIPE_WEBHOOK_SECRET o endpoint nao existe: nunca aceita evento sem assinatura. */
@SpringBootTest(properties = "payment.stripe.webhook-secret=")
@Import(PostgresTestConfiguration.class)
class StripeWebhookControllerDisabledTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void endpointNotRegisteredWithoutSecret() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(post("/api/v1/webhooks/stripe").header("Stripe-Signature", "t=1,v1=abc")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
