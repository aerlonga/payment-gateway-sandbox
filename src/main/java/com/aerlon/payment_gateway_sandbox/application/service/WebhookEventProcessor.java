package com.aerlon.payment_gateway_sandbox.application.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.entities.ProcessedWebhookEvent;
import com.aerlon.payment_gateway_sandbox.domain.exception.InvalidPaymentStateException;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentNotFoundException;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentRepository;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.ProcessedWebhookEventRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Aplica eventos do provider de forma idempotente. Tudo acontece em uma transacao: o registro
 * em processed_webhook_events (UNIQUE decide a corrida), o lock do pagamento e a transicao.
 * Se o pagamento nao existe, a transacao desfaz o registro e a excecao sobe para o Kafka retentar.
 *
 * A violacao do UNIQUE aborta a transacao no Postgres, por isso e tratada fora dela
 * (mesmo padrao de PaymentService.create).
 */
@Service
@Slf4j
public class WebhookEventProcessor {

    private final ProcessedWebhookEventRepository processedRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final TransactionTemplate transactionTemplate;

    public WebhookEventProcessor(ProcessedWebhookEventRepository processedRepository,
            PaymentRepository paymentRepository, PaymentService paymentService,
            PlatformTransactionManager transactionManager) {
        this.processedRepository = processedRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void process(ProviderEvent event) {
        if (event.newStatus() == null) {
            log.debug("Evento ignorado eventId={} type={}", event.eventId(), event.type());
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> apply(event));
        } catch (DataIntegrityViolationException e) {
            log.info("Evento duplicado descartado eventId={} type={}", event.eventId(), event.type());
        }
    }

    private void apply(ProviderEvent event) {
        processedRepository.saveAndFlush(ProcessedWebhookEvent.builder().stripeEventId(event.eventId()).build());

        Payment payment = paymentRepository.findByProviderPaymentIdForUpdate(event.providerPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException(event.providerPaymentId()));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", "stripe_webhook");
        extra.put("stripeEventId", event.eventId());
        try {
            paymentService.applyProviderStatus(payment, event.newStatus(), extra);
        } catch (InvalidPaymentStateException e) {
            log.warn("Evento fora de ordem descartado eventId={} type={} paymentId={} status={}",
                    event.eventId(), event.type(), payment.getId(), payment.getStatus());
        }
    }
}
