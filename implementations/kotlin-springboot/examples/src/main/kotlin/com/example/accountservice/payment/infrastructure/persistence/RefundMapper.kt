package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.domain.Refund

/**
 * Refund(순수 도메인) ↔ RefundJpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * RefundRepositoryImpl 내부에서만 사용된다.
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

    /** 신규 Refund를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
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

    /** 기존 엔티티(PK 보존)에 도메인 Refund의 최신 상태를 반영한다 — update 대상(approve/reject/complete). */
    fun updateEntity(
        entity: RefundJpaEntity,
        refund: Refund,
    ): RefundJpaEntity {
        entity.status = refund.status
        entity.decisionNote = refund.decisionNote
        return entity
    }
}
