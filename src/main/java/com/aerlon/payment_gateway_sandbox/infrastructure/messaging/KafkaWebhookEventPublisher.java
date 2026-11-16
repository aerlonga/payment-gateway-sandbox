package com.aerlon.payment_gateway_sandbox.infrastructure.messaging;

import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.application.port.WebhookEventPublisher;
import com.aerlon.payment_gateway_sandbox.config.KafkaWebhookConfig;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/** Publica no Kafka com chave = providerPaymentId (preserva a ordem por pagamento). */
@Component
@Slf4j
public class KafkaWebhookEventPublisher implements WebhookEventPublisher {

    private static final long ACK_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /** O Boot expoe o template como KafkaTemplate&lt;?, ?&gt;; chave e valor sao sempre String aqui. */
    @SuppressWarnings("unchecked")
    public KafkaWebhookEventPublisher(KafkaTemplate<?, ?> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = (KafkaTemplate<String, String>) kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ProviderEvent event) {
        try {
            kafkaTemplate.send(KafkaWebhookConfig.TOPIC, event.providerPaymentId(),
                    objectMapper.writeValueAsString(event)).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Evento publicado eventId={} type={}", event.eventId(), event.type());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicacao do evento de webhook interrompida", e);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao publicar evento de webhook eventId=" + event.eventId(), e);
        }
    }
}
