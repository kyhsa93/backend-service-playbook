package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.domain.Refund;

/** Refund(순수 도메인) ↔ RefundJpaEntity(JPA 매핑) 변환 전담 클래스. RefundRepositoryImpl 내부에서만 사용된다. */
final class RefundMapper {

    private RefundMapper() {}

    static Refund toDomain(RefundJpaEntity entity) {
        return Refund.reconstitute(
                entity.getRefundId(),
                entity.getPaymentId(),
                entity.getAmount(),
                entity.getReason(),
                entity.getStatus(),
                entity.getDecisionNote(),
                entity.getCreatedAt());
    }

    static RefundJpaEntity toNewEntity(Refund refund) {
        return new RefundJpaEntity(
                null,
                refund.getRefundId(),
                refund.getPaymentId(),
                refund.getAmount(),
                refund.getReason(),
                refund.getStatus(),
                refund.getDecisionNote(),
                refund.getCreatedAt());
    }

    static RefundJpaEntity updateEntity(RefundJpaEntity entity, Refund refund) {
        entity.applyMutableState(refund.getStatus(), refund.getDecisionNote());
        return entity;
    }
}
