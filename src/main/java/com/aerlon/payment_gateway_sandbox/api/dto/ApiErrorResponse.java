package com.aerlon.payment_gateway_sandbox.api.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Formato padrao de erro da API")
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldErrorItem> fieldErrors) {

    public record FieldErrorItem(String field, String message) {
    }

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, List.of());
    }
}
