package com.aerlon.payment_gateway_sandbox.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.aerlon.payment_gateway_sandbox.domain.entities.Payment;
import com.aerlon.payment_gateway_sandbox.domain.enums.PaymentStatus;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    Page<Payment> findByCustomerId(UUID customerId, Pageable pageable);

    Page<Payment> findByCustomerIdAndStatus(UUID customerId, PaymentStatus status, Pageable pageable);
}
