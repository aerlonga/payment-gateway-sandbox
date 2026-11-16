package com.aerlon.payment_gateway_sandbox.infrastructure.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.aerlon.payment_gateway_sandbox.application.port.ProviderEvent;
import com.aerlon.payment_gateway_sandbox.application.service.WebhookEventProcessor;
import com.aerlon.payment_gateway_sandbox.config.KafkaWebhookConfig;

import tools.jackson.databind.ObjectMapper;

/** Consome o topico de webhooks; falhas sobem para o DefaultErrorHandler (retry e DLT). */
@Component
public class WebhookEventListener {

    private final ObjectMapper objectMapper;
    private final WebhookEventProcessor processor;

    public WebhookEventListener(ObjectMapper objectMapper, WebhookEventProcessor processor) {
        this.objectMapper = objectMapper;
        this.processor = processor;
    }

    @KafkaListener(topics = KafkaWebhookConfig.TOPIC, groupId = KafkaWebhookConfig.GROUP_ID)
    public void onMessage(String message) {
        processor.process(objectMapper.readValue(message, ProviderEvent.class));
    }
}
