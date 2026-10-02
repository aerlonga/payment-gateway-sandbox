package com.aerlon.payment_gateway_sandbox.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.aerlon.payment_gateway_sandbox.domain.entities.PaymentEvent;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
