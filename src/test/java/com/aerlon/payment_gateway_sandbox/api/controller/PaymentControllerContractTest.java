package com.aerlon.payment_gateway_sandbox.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;
import com.aerlon.payment_gateway_sandbox.api.exception.GlobalExceptionHandler;
import com.aerlon.payment_gateway_sandbox.application.service.CreatePaymentResult;
import com.aerlon.payment_gateway_sandbox.application.service.PaymentService;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

/** Contrato HTTP do POST/GET (status, headers e JSON) com o service mockado. */
class PaymentControllerContractTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private PaymentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PaymentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PaymentResponse payment(String clientSecret) {
        return new PaymentResponse(ID, CUSTOMER, 15000L, "BRL", 0L, PaymentStatus.PENDING, "stripe", "pi_1",
                "card", "Pedido #1234", Instant.now(), Instant.now(), clientSecret);
    }

    private static String body(String paymentMethod, String description) {
        return """
                {"customerId":"%s","amount":15000,"currency":"BRL","paymentMethod":%s,"description":%s}
                """.formatted(CUSTOMER,
                paymentMethod == null ? "null" : "\"" + paymentMethod + "\"",
                description == null ? "null" : "\"" + description + "\"");
    }

    @Test
    void createReturns201WithLocationAndClientSecret() throws Exception {
        when(service.create(any(), eq("k1"))).thenReturn(new CreatePaymentResult(payment("pi_1_secret_x"), true));

        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("card", "Pedido #1234")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/payments/" + ID))
                .andExpect(jsonPath("$.clientSecret").value("pi_1_secret_x"))
                .andExpect(jsonPath("$.paymentMethod").value("card"))
                .andExpect(jsonPath("$.description").value("Pedido #1234"));
    }

    @Test
    void replayReturns200WithoutClientSecretFieldWhenAbsent() throws Exception {
        when(service.create(any(), eq("k1"))).thenReturn(new CreatePaymentResult(payment(null), false));

        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void getNeverExposesClientSecret() throws Exception {
        when(service.findById(ID)).thenReturn(payment(null));

        mvc.perform(get("/api/v1/payments/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void invalidPaymentMethodAndDescriptionReturn400() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("CARD!", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("paymentMethod"));

        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "x".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("description"));
    }
}
