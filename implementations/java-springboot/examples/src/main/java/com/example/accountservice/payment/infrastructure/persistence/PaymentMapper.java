package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.domain.Payment;

/**
 * A class dedicated to converting between Payment (pure domain) and PaymentJpaEntity (JPA mapping).
 * Used only inside PaymentRepositoryImpl — the Domain/Application layers are unaware of this class.
 */
final class PaymentMapper {

    private PaymentMapper() {}

    static Payment toDomain(PaymentJpaEntity entity) {
        return Payment.reconstitute(
                entity.getPaymentId(),
                entity.getCardId(),
                entity.getAccountId(),
                entity.getOwnerId(),
                entity.getAmount(),
                entity.getStatus(),
                entity.getCreatedAt());
    }

    /** Creates a new entity (no PK, an insert target) for a new Payment. */
    static PaymentJpaEntity toNewEntity(Payment payment) {
        return new PaymentJpaEntity(
                null,
                payment.getPaymentId(),
                payment.getCardId(),
                payment.getAccountId(),
                payment.getOwnerId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getCreatedAt());
    }

    /**
     * Applies the domain Payment's latest state to an existing entity (preserving PK) — an update
     * target.
     */
    static PaymentJpaEntity updateEntity(PaymentJpaEntity entity, Payment payment) {
        entity.applyMutableState(payment.getStatus());
        return entity;
    }
}
