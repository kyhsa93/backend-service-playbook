package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.domain.Refund

/**
 * The object dedicated to converting between Refund (pure domain) and RefundJpaEntity (JPA mapping).
 * Used only inside RefundRepositoryImpl.
 */
internal object RefundMapper {
    fun toDomain(entity: RefundJpaEntity): Refund =
        Refund.reconstitute(
            refundId = entity.refundId,
            paymentId = entity.paymentId,
            amount = entity.amount,
            reason = entity.reason,
            status = entity.status,
            decisionNote = entity.decisionNote,
            createdAt = entity.createdAt,
        )

    /** Creates a new entity (no PK, an insert target) for a new Refund. */
    fun toNewEntity(refund: Refund): RefundJpaEntity =
        RefundJpaEntity(
            id = null,
            refundId = refund.refundId,
            paymentId = refund.paymentId,
            amount = refund.amount,
            reason = refund.reason,
            status = refund.status,
            decisionNote = refund.decisionNote,
            createdAt = refund.createdAt,
        )

    /** Reflects the domain Refund's latest state onto the existing entity (preserving the PK) — an update target (approve/reject/complete). */
    fun updateEntity(
        entity: RefundJpaEntity,
        refund: Refund,
    ): RefundJpaEntity {
        entity.status = refund.status
        entity.decisionNote = refund.decisionNote
        return entity
    }
}
