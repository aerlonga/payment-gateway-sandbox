package com.aerlon.payment_gateway_sandbox.api.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.aerlon.payment_gateway_sandbox.api.dto.CreatePaymentRequest;
import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;
import com.aerlon.payment_gateway_sandbox.application.service.CreatePaymentResult;
import com.aerlon.payment_gateway_sandbox.application.service.PaymentService;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Criacao e consulta de pagamentos")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @Operation(summary = "Cria um pagamento (idempotente por Idempotency-Key)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Replay idempotente: pagamento ja existia com o mesmo payload"),
            @ApiResponse(responseCode = "201", description = "Pagamento criado"),
            @ApiResponse(responseCode = "400", description = "Payload invalido"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key reutilizada com payload diferente")
    })
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            UriComponentsBuilder uriBuilder) {

        CreatePaymentResult result = paymentService.create(request, idempotencyKey);
        PaymentResponse response = result.payment();
        URI location = uriBuilder.path("/api/v1/payments/{id}").buildAndExpand(response.id()).toUri();

        return result.created()
                ? ResponseEntity.created(location).body(response)
                : ResponseEntity.ok().location(location).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca um pagamento pelo id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagamento encontrado"),
            @ApiResponse(responseCode = "404", description = "Pagamento nao encontrado")
    })
    public ResponseEntity<PaymentResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.findById(id));
    }

    @GetMapping
    @Operation(summary = "Lista pagamentos de um cliente, opcionalmente filtrando por status")
    public ResponseEntity<Page<PaymentResponse>> listByCustomer(
            @RequestParam UUID customerId,
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(paymentService.listByCustomer(customerId, status, pageable));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancela um pagamento ainda nao capturado")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagamento cancelado"),
            @ApiResponse(responseCode = "404", description = "Pagamento nao encontrado"),
            @ApiResponse(responseCode = "409", description = "Transicao de status invalida")
    })
    public ResponseEntity<PaymentResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.cancel(id));
    }
}
