package com.aerlon.payment_gateway_sandbox.application.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aerlon.payment_gateway_sandbox.api.dto.CreatePaymentRequest;
import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;
import com.aerlon.payment_gateway_sandbox.application.mapper.PaymentMapper;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.IdempotencyConflictException;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidPaymentStateException;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentNotFoundException;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** Transicoes permitidas na maquina de estados do pagamento. */
    private static final Map<PaymentStatus, EnumSet<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PaymentStatus.PENDING, EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED, PaymentStatus.CANCELED),
            PaymentStatus.AUTHORIZED, EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED, PaymentStatus.CANCELED),
            PaymentStatus.CAPTURED, EnumSet.of(PaymentStatus.REFUNDED),
            PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class),
            PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentStatus.class),
            PaymentStatus.CANCELED, EnumSet.noneOf(PaymentStatus.class));

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    /**
     * Cria um pagamento. Se a Idempotency-Key ja existir, devolve o pagamento
     * original quando o payload for igual, ou falha em caso de divergencia.
     */
    @Transactional
    public CreatePaymentResult create(CreatePaymentRequest request, String idempotencyKey) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!matchesRequest(payment, request)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            log.debug("Replay idempotente para key={} payment={}", idempotencyKey, payment.getId());
            return CreatePaymentResult.replayed(paymentMapper.toResponse(payment));
        }

        Payment saved = paymentRepository.save(paymentMapper.toEntity(request, idempotencyKey));
        log.info("Pagamento criado id={} customer={} amount={} {}",
                saved.getId(), saved.getCustomerId(), saved.getAmount(), saved.getCurrency());
        return CreatePaymentResult.created(paymentMapper.toResponse(saved));
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(UUID id) {
        return paymentMapper.toResponse(getOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> listByCustomer(UUID customerId, PaymentStatus status, Pageable pageable) {
        Page<Payment> page = (status == null)
                ? paymentRepository.findByCustomerId(customerId, pageable)
                : paymentRepository.findByCustomerIdAndStatus(customerId, status, pageable);
        return page.map(paymentMapper::toResponse);
    }

    /** Aplica uma transicao de status, validando a maquina de estados. */
    @Transactional
    public PaymentResponse changeStatus(UUID id, PaymentStatus newStatus, String providerPaymentId) {
        Payment payment = getOrThrow(id);
        PaymentStatus current = payment.getStatus();

        if (current == newStatus) {
            return paymentMapper.toResponse(payment);
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(PaymentStatus.class)).contains(newStatus)) {
            throw new InvalidPaymentStateException(current, newStatus);
        }

        payment.setStatus(newStatus);
        payment.setUpdatedAt(Instant.now());
        if (providerPaymentId != null) {
            payment.setProviderPaymentId(providerPaymentId);
        }
        log.info("Pagamento id={} {} -> {}", id, current, newStatus);
        return paymentMapper.toResponse(payment);
    }

    @Transactional
    public PaymentResponse cancel(UUID id) {
        return changeStatus(id, PaymentStatus.CANCELED, null);
    }

    private Payment getOrThrow(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    private boolean matchesRequest(Payment payment, CreatePaymentRequest request) {
        return payment.getCustomerId().equals(request.customerId())
                && payment.getAmount().equals(request.amount())
                && payment.getCurrency().equalsIgnoreCase(request.currency())
                && payment.getProvider() == request.provider();
    }
}
