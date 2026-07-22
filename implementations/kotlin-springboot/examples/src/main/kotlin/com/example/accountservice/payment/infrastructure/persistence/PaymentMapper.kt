package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.domain.Payment

/**
 * The object dedicated to converting between Payment (pure domain) and PaymentJpaEntity (JPA mapping).
 * Used only inside PaymentRepositoryImpl — the Domain/Application layers know nothing about this object.
 */
internal object PaymentMapper {
    fun toDomain(entity: PaymentJpaEntity): Payment =
        Payment.reconstitute(
            paymentId = entity.paymentId,
            cardId = entity.cardId,
            accountId = entity.accountId,
            ownerId = entity.ownerId,
            amount = entity.amount,
            status = entity.status,
            createdAt = entity.createdAt,
        )

    /** Creates a new entity (no PK, an insert target) for a new Payment. */
    fun toNewEntity(payment: Payment): PaymentJpaEntity =
        PaymentJpaEntity(
            id = null,
            paymentId = payment.paymentId,
            cardId = payment.cardId,
            accountId = payment.accountId,
            ownerId = payment.ownerId,
            amount = payment.amount,
            status = payment.status,
            createdAt = payment.createdAt,
        )

    /** Reflects the domain Payment's latest state (status) onto the existing entity (preserving the PK) — an update target. */
    fun updateEntity(
        entity: PaymentJpaEntity,
        payment: Payment,
    ): PaymentJpaEntity {
        entity.status = payment.status
        return entity
    }
}
