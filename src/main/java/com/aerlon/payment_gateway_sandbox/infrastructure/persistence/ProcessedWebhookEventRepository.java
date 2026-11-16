package com.aerlon.payment_gateway_sandbox.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.aerlon.payment_gateway_sandbox.domain.entities.ProcessedWebhookEvent;

@Repository
public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, UUID> {

    boolean existsByStripeEventId(String stripeEventId);
}
