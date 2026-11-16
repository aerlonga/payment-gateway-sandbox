package com.aerlon.payment_gateway_sandbox.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aerlon.payment_gateway_sandbox.PostgresTestConfiguration;
import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.domain.exception.PaymentNotFoundException;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentRepository;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class WebhookEventProcessorTest {

    @Autowired
    private WebhookEventProcessor processor;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Payment newPayment(PaymentStatus status) {
        return paymentRepository.saveAndFlush(Payment.builder()
                .customerId(UUID.randomUUID())
                .amount(10000L)
                .currency("BRL")
                .status(status)
                .provider("stripe")
                .providerPaymentId("pi_" + UUID.randomUUID())
                .idempotencyKey("wh-" + UUID.randomUUID())
                .build());
    }

    private ProviderEvent succeeded(String eventId, Payment p) {
        return new ProviderEvent(eventId, "payment_intent.succeeded", p.getProviderPaymentId(), PaymentStatus.CAPTURED);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void succeededEventMovesPendingToCaptured() {
        Payment p = newPayment(PaymentStatus.PENDING);
        String evt = "evt_" + UUID.randomUUID();

        processor.process(succeeded(evt, p));

        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(count("select count(*) from payment_events where payment_id = ? and event_type = 'payment.confirmed'"
                + " and payload->>'source' = 'stripe_webhook' and payload->>'stripeEventId' = ?",
                p.getId(), evt)).isEqualTo(1);
        assertThat(count("select count(*) from processed_webhook_events where stripe_event_id = ?", evt)).isEqualTo(1);
    }

    @Test
    void duplicateEventIsIgnored() {
        Payment p = newPayment(PaymentStatus.PENDING);
        String evt = "evt_" + UUID.randomUUID();

        processor.process(succeeded(evt, p));
        processor.process(succeeded(evt, p));

        assertThat(count("select count(*) from payment_events where payment_id = ? and event_type = 'payment.confirmed'",
                p.getId())).isEqualTo(1);
        assertThat(count("select count(*) from processed_webhook_events where stripe_event_id = ?", evt)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateEventAppliesOnce() throws Exception {
        Payment p = newPayment(PaymentStatus.PENDING);
        String evt = "evt_" + UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    processor.process(succeeded(evt, p));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();
        for (Future<Throwable> f : futures) {
            assertThat(f.get()).isNull();
        }
        pool.shutdown();

        assertThat(count("select count(*) from payment_events where payment_id = ? and event_type = 'payment.confirmed'",
                p.getId())).isEqualTo(1);
        assertThat(count("select count(*) from processed_webhook_events where stripe_event_id = ?", evt)).isEqualTo(1);
    }

    @Test
    void outOfOrderEventIsSkippedAndMarkedProcessed() {
        Payment p = newPayment(PaymentStatus.CAPTURED);
        String evt = "evt_" + UUID.randomUUID();

        processor.process(new ProviderEvent(evt, "payment_intent.payment_failed", p.getProviderPaymentId(),
                PaymentStatus.FAILED));

        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(count("select count(*) from processed_webhook_events where stripe_event_id = ?", evt)).isEqualTo(1);
    }

    @Test
    void unknownPaymentThrowsPaymentNotFound() {
        String evt = "evt_" + UUID.randomUUID();

        assertThatThrownBy(() -> processor.process(
                new ProviderEvent(evt, "payment_intent.succeeded", "pi_inexistente", PaymentStatus.CAPTURED)))
                .isInstanceOf(PaymentNotFoundException.class);

        assertThat(count("select count(*) from processed_webhook_events where stripe_event_id = ?", evt)).isZero();
    }

    @Test
    void eventWithNullNewStatusIsIgnored() {
        Payment p = newPayment(PaymentStatus.PENDING);
        String evt = "evt_" + UUID.randomUUID();

        processor.process(new ProviderEvent(evt, "customer.created", p.getProviderPaymentId(), null));

        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(count("select count(*) from processed_webhook_events where stripe_event_id = ?", evt)).isZero();
    }
}
