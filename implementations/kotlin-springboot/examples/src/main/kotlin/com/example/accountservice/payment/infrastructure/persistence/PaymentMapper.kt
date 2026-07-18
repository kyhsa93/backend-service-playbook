package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.domain.Payment

/**
 * Payment(순수 도메인) ↔ PaymentJpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * PaymentRepositoryImpl 내부에서만 사용된다 — Domain/Application 레이어는 이 오브젝트를 알지 못한다.
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

    /** 신규 Payment를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
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

    /** 기존 엔티티(PK 보존)에 도메인 Payment의 최신 상태(status)를 반영한다 — update 대상. */
    fun updateEntity(
        entity: PaymentJpaEntity,
        payment: Payment,
    ): PaymentJpaEntity {
        entity.status = payment.status
        return entity
    }
}
