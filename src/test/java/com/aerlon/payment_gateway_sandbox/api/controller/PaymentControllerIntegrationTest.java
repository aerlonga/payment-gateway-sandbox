package com.aerlon.payment_gateway_sandbox.api.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.aerlon.payment_gateway_sandbox.PostgresTestConfiguration;
import com.jayway.jsonpath.JsonPath;

/**
 * Fluxo HTTP completo com contexto Spring real: controller, validacao, service,
 * handler de erros, JPA e Flyway contra Postgres (Testcontainers) e provider fake.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentControllerIntegrationTest {

    private static final String URL = "/api/v1/payments";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static String createBody(UUID customerId, long amount) {
        return """
                {"customerId":"%s","amount":%d,"currency":"BRL","paymentMethod":"card","description":"Pedido #1"}
                """.formatted(customerId, amount);
    }

    private String create(UUID customerId, long amount) throws Exception {
        MvcResult result = mvc.perform(post(URL).header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(customerId, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void refund(String id, String body) throws Exception {
        mvc.perform(post(URL + "/" + id + "/refund").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void createReturns201WithLocationAndCapturedPayment() throws Exception {
        UUID customer = UUID.randomUUID();

        MvcResult result = mvc.perform(post(URL).header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(customer, 15000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.provider").value("fake"))
                .andExpect(jsonPath("$.clientSecret").exists())
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Location"))
                .endsWith(URL + "/" + id);
    }

    @Test
    void idempotentReplayReturns200AndSamePayment() throws Exception {
        String key = "it-" + UUID.randomUUID();
        String body = createBody(UUID.randomUUID(), 9900);

        MvcResult first = mvc.perform(post(URL).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mvc.perform(post(URL).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "http://localhost" + URL + "/" + id))
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void sameKeyWithDifferentPayloadReturns409() throws Exception {
        String key = "it-" + UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        mvc.perform(post(URL).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(customer, 1000)))
                .andExpect(status().isCreated());
        mvc.perform(post(URL).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(customer, 2000)))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidPayloadAndMissingHeaderReturn400() throws Exception {
        mvc.perform(post(URL).header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), -5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), 1000)))
                .andExpect(status().isBadRequest());

        mvc.perform(post(URL).header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{nao-e-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPaymentReturns404() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(get(URL + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/" + id + "/history")).andExpect(status().isNotFound());
        mvc.perform(post(URL + "/" + id + "/cancel")).andExpect(status().isNotFound());
        mvc.perform(post(URL + "/" + id + "/refund").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void partialThenTotalRefundUpdatesBalanceStatusAndHistory() throws Exception {
        String id = create(UUID.randomUUID(), 10000);

        mvc.perform(post(URL + "/" + id + "/refund").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":4000,\"reason\":\"parcial\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.refundedAmount").value(4000));

        refund(id, "{}");

        mvc.perform(get(URL + "/" + id))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(10000));

        mvc.perform(get(URL + "/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].eventType").value(contains("payment.created", "payment.confirmed", "payment.refunded", "payment.refunded")));
    }

    @Test
    void refundAboveBalanceReturns400AndRefundOfRefundedReturns409() throws Exception {
        String id = create(UUID.randomUUID(), 5000);

        mvc.perform(post(URL + "/" + id + "/refund").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5001}"))
                .andExpect(status().isBadRequest());

        refund(id, "{}");

        mvc.perform(post(URL + "/" + id + "/refund").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelOfCapturedPaymentReturns409() throws Exception {
        String id = create(UUID.randomUUID(), 3000);

        mvc.perform(post(URL + "/" + id + "/cancel")).andExpect(status().isConflict());
    }

    @Test
    void listByCustomerPaginatesAndFiltersByStatus() throws Exception {
        UUID customer = UUID.randomUUID();
        String refunded = create(customer, 1000);
        create(customer, 2000);
        create(customer, 3000);
        create(UUID.randomUUID(), 4000);
        refund(refunded, "{}");

        mvc.perform(get(URL).param("customerId", customer.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get(URL).param("customerId", customer.toString()).param("size", "2"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get(URL).param("customerId", customer.toString()).param("status", "REFUNDED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(refunded));

        mvc.perform(get(URL).param("customerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
