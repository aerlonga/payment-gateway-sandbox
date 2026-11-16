package com.aerlon.payment_gateway_sandbox.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Topico dos eventos de webhook e tratamento de erro do consumer: 5 retentativas com 1 s de
 * intervalo (cobre o webhook que chega antes do commit do pagamento) e depois dead-letter
 * em {@code payment.webhook-events.DLT}, sem derrubar o consumer.
 */
@Configuration
public class KafkaWebhookConfig {

    public static final String TOPIC = "payment.webhook-events";
    public static final String DLT = TOPIC + ".DLT";
    public static final String GROUP_ID = "payment-gateway-webhook";

    @Bean
    NewTopic webhookEventsTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic webhookEventsDlt() {
        return TopicBuilder.name(DLT).partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler webhookErrorHandler(KafkaTemplate<?, ?> template) {
        // destino explicito: o sufixo default do spring-kafka 4 e "-dlt", nao ".DLT"; particao -1 = o producer decide
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(DLT, -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 5));
    }
}
