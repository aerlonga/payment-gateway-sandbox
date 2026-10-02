package com.aerlon.payment_gateway_sandbox.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Dados para estorno de um pagamento")
public record RefundRequest(

        @Schema(description = "Valor em centavos. Omitido = estorno total", example = "5000")
        @Positive(message = "amount deve ser maior que zero")
        Long amount,

        @Schema(example = "Cliente desistiu da compra")
        @Size(max = 255, message = "reason deve ter no maximo 255 caracteres")
        String reason) {
}
