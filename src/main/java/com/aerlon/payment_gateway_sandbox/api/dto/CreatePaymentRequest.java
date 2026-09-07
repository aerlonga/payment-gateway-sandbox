package com.aerlon.payment_gateway_sandbox.api.dto;

import java.util.UUID;

import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentProvider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Schema(description = "Dados para criacao de um pagamento")
public record CreatePaymentRequest(

        @Schema(example = "3f5b1c2e-1d3a-4a1b-9c0d-2b7f8e6a1234")
        @NotNull(message = "customerId e obrigatorio")
        UUID customerId,

        @Schema(description = "Valor em centavos", example = "19990")
        @NotNull(message = "amount e obrigatorio")
        @Positive(message = "amount deve ser maior que zero")
        Long amount,

        @Schema(description = "Moeda ISO-4217", example = "BRL")
        @NotNull(message = "currency e obrigatorio")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency deve ter 3 letras maiusculas (ISO-4217)")
        String currency,

        @Schema(example = "STRIPE")
        @NotNull(message = "provider e obrigatorio")
        PaymentProvider provider) {
}
