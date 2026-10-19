package com.aerlon.payment_gateway_sandbox.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import com.aerlon.payment_gateway_sandbox.api.dto.CreatePaymentRequest;
import com.aerlon.payment_gateway_sandbox.domain.exception.IdempotencyConflictException;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentIdempotencyRaceTest {

    private static final int THREADS = 16;

    @Autowired
    private PaymentService service;

    @Autowired
    private JdbcTemplate jdbc;

    /** Dispara todas as chamadas ao mesmo tempo e devolve resultado ou excecao de cada uma. */
    private List<Object> race(List<Callable<CreatePaymentResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<CreatePaymentResult> call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return call.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    void sameKeyAndPayloadInParallelCreatesOnlyOnePayment() throws Exception {
        String key = "race-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(UUID.randomUUID(), 15000L, "BRL");

        List<Callable<CreatePaymentResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> service.create(request, key));
        }
        List<Object> results = race(calls);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(CreatePaymentResult.class));
        List<CreatePaymentResult> ok = results.stream().map(CreatePaymentResult.class::cast).toList();
        assertThat(ok.stream().filter(CreatePaymentResult::created)).hasSize(1);
        assertThat(ok.stream().map(r -> r.payment().id()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_events e join payments p on p.id = e.payment_id "
                + "where p.idempotency_key = ? and e.event_type = 'payment.created'", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadInParallelConflicts() throws Exception {
        String key = "race-" + UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        List<Callable<CreatePaymentResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long amount = 1000L + i; // payloads todos diferentes
            calls.add(() -> service.create(new CreatePaymentRequest(customer, amount, "BRL"), key));
        }
        List<Object> results = race(calls);

        assertThat(results.stream().filter(CreatePaymentResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(IdempotencyConflictException.class::isInstance)).hasSize(THREADS - 1);
        assertThat(jdbc.queryForObject("select count(*) from payments where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
    }
}
