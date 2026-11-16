package com.aerlon.payment_gateway_sandbox.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.aerlon.payment_gateway_sandbox.PostgresTestConfiguration;
import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;
import com.aerlon.payment_gateway_sandbox.infrastructure.persistence.PaymentRepository;
import com.stripe.net.Webhook;

/** Webhook assinado -> Kafka -> consumer -> Postgres, com broker e banco reais. */
@SpringBootTest(properties = {
        "payment.stripe.webhook-secret=test-only-signing-secret",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true" })
@Import({ PostgresTestConfiguration.class, WebhookFlowIntegrationTest.KafkaConfig.class })
class WebhookFlowIntegrationTest {

    private static final String SECRET = "test-only-signing-secret";

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaConfig {
        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"));
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private KafkaContainer kafka;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Payment newPendingPayment(String providerPaymentId) {
        return paymentRepository.saveAndFlush(Payment.builder()
                .customerId(UUID.randomUUID()).amount(10000L).currency("BRL")
                .status(PaymentStatus.PENDING).provider("stripe").providerPaymentId(providerPaymentId)
                .idempotencyKey("flow-" + UUID.randomUUID()).build());
    }

    private void sendSucceeded(String eventId, String providerPaymentId) throws Exception {
        String body = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"" + providerPaymentId + "\",\"object\":\"payment_intent\"}}}";
        String header = Webhook.Signature.generateSignatureHeader(body, SECRET, Webhook.Util.getTimeNow());
        mvc.perform(post("/api/v1/webhooks/stripe").header("Stripe-Signature", header)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private PaymentStatus statusOf(Payment p) {
        return paymentRepository.findById(p.getId()).orElseThrow().getStatus();
    }

    @Test
    void signedSucceededEventCapturesPayment() throws Exception {
        Payment p = newPendingPayment("pi_flow_" + UUID.randomUUID());

        sendSucceeded("evt_" + UUID.randomUUID(), p.getProviderPaymentId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(statusOf(p)).isEqualTo(PaymentStatus.CAPTURED));
    }

    @Test
    void eventBeforePaymentExistsGoesToDltAndConsumerKeepsWorking() throws Exception {
        String missing = "pi_missing_" + UUID.randomUUID();
        sendSucceeded("evt_" + UUID.randomUUID(), missing);

        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            dlt.subscribe(List.of("payment.webhook-events.DLT"));
            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                boolean found = false;
                for (ConsumerRecord<String, String> r : dlt.poll(Duration.ofMillis(500))) {
                    found |= missing.equals(r.key());
                }
                assertThat(found).isTrue();
            });
        }

        Payment p = newPendingPayment("pi_flow_" + UUID.randomUUID());
        sendSucceeded("evt_" + UUID.randomUUID(), p.getProviderPaymentId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(statusOf(p)).isEqualTo(PaymentStatus.CAPTURED));
    }
}
