package com.aerlon.payment_gateway_sandbox.domain.enums;

/** Estado de publicacao do evento no Kafka (outbox pattern). */
public enum EventPublishStatus {
    PENDING_PUBLISH,
    PUBLISHED,
    FAILED
}
