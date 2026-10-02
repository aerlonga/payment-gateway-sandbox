package com.aerlon.payment_gateway_sandbox.application.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aerlon.payment_gateway_sandbox.api.dto.CreatePaymentRequest;
import com.aerlon.payment_gateway_sandbox.api.dto.PaymentEventResponse;
import com.aerlon.payment_gateway_sandbox.api.dto.PaymentResponse;
import com.aerlon.payment_gateway_sandbox.api.dto.RefundRequest;
import com.aerlon.payment_gateway_sandbox.application.mapper.PaymentMapper;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.entities.PaymentEvent;
import com.aerlon.payment_gateway_sandbox.domain.enums.EventPublishStatus;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentEventType;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.IdempotencyConflictException;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidPaymentStateException;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidRefundException;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentNotFoundException;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentEventRepository;
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
    private final PaymentEventRepository paymentEventRepository;
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
        recordEvent(saved, PaymentEventType.CREATED, Map.of());
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
        return paymentMapper.toResponse(transition(getOrThrow(id), newStatus, providerPaymentId, Map.of()));
    }

    @Transactional(readOnly = true)
    public List<PaymentEventResponse> history(UUID id) {
        if (!paymentRepository.existsById(id)) {
            throw new PaymentNotFoundException(id);
        }
        return paymentEventRepository.findByPaymentIdOrderByCreatedAtAsc(id).stream()
                .map(paymentMapper::toEventResponse)
                .toList();
    }

    @Transactional
    public PaymentResponse cancel(UUID id) {
        return changeStatus(id, PaymentStatus.CANCELED, null);
    }

    /**
     * Estorna um pagamento capturado. amount nulo = estorno total. O valor nao
     * pode exceder o capturado. Ainda nao ha controle de saldo estornado:
     * qualquer estorno leva o pagamento a REFUNDED.
     */
    @Transactional
    public PaymentResponse refund(UUID id, RefundRequest request) {
        Payment payment = getOrThrow(id);
        if (request.amount() != null && request.amount() > payment.getAmount()) {
            throw new InvalidRefundException("Valor do estorno (" + request.amount()
                    + ") excede o valor capturado (" + payment.getAmount() + ")");
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("refundedAmount", request.amount() != null ? request.amount() : payment.getAmount());
        extra.put("reason", request.reason());
        return paymentMapper.toResponse(transition(payment, PaymentStatus.REFUNDED, null, extra));
    }

    private Payment transition(Payment payment, PaymentStatus newStatus, String providerPaymentId,
            Map<String, Object> extraPayload) {
        PaymentStatus current = payment.getStatus();
        if (current == newStatus) {
            return payment;
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(PaymentStatus.class)).contains(newStatus)) {
            throw new InvalidPaymentStateException(current, newStatus);
        }

        payment.setStatus(newStatus);
        payment.setUpdatedAt(Instant.now());
        if (providerPaymentId != null) {
            payment.setProviderPaymentId(providerPaymentId);
        }

        Map<String, Object> payload = new LinkedHashMap<>(extraPayload);
        payload.put("from", current.name());
        recordEvent(payment, PaymentEventType.forStatus(newStatus), payload);
        log.info("Pagamento id={} {} -> {}", payment.getId(), current, newStatus);
        return payment;
    }

    /** Grava o evento na mesma transacao da mudanca (base do outbox). */
    private void recordEvent(Payment payment, PaymentEventType type, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getId().toString());
        payload.put("status", payment.getStatus().name());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("occurredAt", Instant.now().toString());
        payload.putAll(extra);

        paymentEventRepository.save(PaymentEvent.builder()
                .paymentId(payment.getId())
                .eventType(type.value())
                .payload(payload)
                .status(EventPublishStatus.PENDING_PUBLISH)
                .build());
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
