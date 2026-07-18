package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.domain.Payment;

/**
 * Payment(순수 도메인) ↔ PaymentJpaEntity(JPA 매핑) 변환 전담 클래스. PaymentRepositoryImpl 내부에서만 사용된다 —
 * Domain/Application 레이어는 이 클래스를 알지 못한다.
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

    /** 신규 Payment를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
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

    /** 기존 엔티티(PK 보존)에 도메인 Payment의 최신 상태를 반영한다 — update 대상. */
    static PaymentJpaEntity updateEntity(PaymentJpaEntity entity, Payment payment) {
        entity.applyMutableState(payment.getStatus());
        return entity;
    }
}
