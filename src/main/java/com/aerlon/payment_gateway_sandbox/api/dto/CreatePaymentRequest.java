package com.aerlon.payment_gateway_sandbox.api.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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

        @Schema(description = "Tipo do meio de pagamento (ex.: card). Omitido = o provider decide", example = "card")
        @Pattern(regexp = "^[a-z][a-z_]{1,29}$", message = "paymentMethod deve conter apenas letras minusculas e underscore")
        String paymentMethod,

        @Schema(example = "Pedido #1234")
        @Size(max = 255, message = "description deve ter no maximo 255 caracteres")
        String description) {
}
