package com.aerlon.payment_gateway_sandbox.api.exception;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import com.aerlon.payment_gateway_sandbox.api.dto.ApiErrorResponse;
import com.aerlon.payment_gateway_sandbox.domain.exception.IdempotencyConflictException;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidPaymentStateException;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentNotFoundException;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(PaymentNotFoundException ex, WebRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({ InvalidPaymentStateException.class, IdempotencyConflictException.class })
    public ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException ex, WebRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        List<ApiErrorResponse.FieldErrorItem> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldErrorItem(error.getField(), error.getDefaultMessage()))
                .toList();

        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Falha de validacao no payload",
                path(request),
                fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Spring 6.1+ envolve as violacoes em HandlerMethodValidationException quando
     * o metodo do controller tem constraints direto nos parametros (ex.: header).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
            WebRequest request) {

        List<ApiErrorResponse.FieldErrorItem> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiErrorResponse.FieldErrorItem(
                                (error instanceof FieldError fieldError)
                                        ? fieldError.getField()
                                        : result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();

        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Falha de validacao na requisicao",
                path(request),
                fieldErrors));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Header obrigatorio ausente: " + ex.getHeaderName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Corpo da requisicao invalido ou mal formado", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Erro inesperado", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado", request);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String message, WebRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), status.getReasonPhrase(), message, path(request)));
    }

    private String path(WebRequest request) {
        return (request instanceof ServletWebRequest servletRequest)
                ? servletRequest.getRequest().getRequestURI()
                : "";
    }
}
