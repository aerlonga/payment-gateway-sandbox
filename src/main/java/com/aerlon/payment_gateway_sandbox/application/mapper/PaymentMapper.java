package com.aerlon.payment_gateway_sandbox.application.mapper;

import org.springframework.stereotype.Component;

import com.aerlon.payment_gateway_sandbox.api.dto.CreatePaymentRequest;
import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

@Component
public class PaymentMapper {

    /** DTO de entrada -> entidade nova (sempre nasce PENDING). */
    public Payment toEntity(CreatePaymentRequest request, String idempotencyKey) {
        return Payment.builder()
                .customerId(request.customerId())
                .amount(request.amount())
                .currency(request.currency().toUpperCase())
                .status(PaymentStatus.PENDING)
                .provider(request.provider())
                .idempotencyKey(idempotencyKey)
                .build();
    }

    /** Entidade -> DTO de saida. */
    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getProvider(),
                payment.getProviderPaymentId(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
