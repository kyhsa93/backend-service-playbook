package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.domain.Refund;

/**
 * A class dedicated to converting between Refund (pure domain) and RefundJpaEntity (JPA mapping).
 * Used only inside RefundRepositoryImpl.
 */
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
                entity.getReasonCategory(),
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
                refund.getReasonCategory(),
                refund.getCreatedAt());
    }

    static RefundJpaEntity updateEntity(RefundJpaEntity entity, Refund refund) {
        entity.applyMutableState(
                refund.getStatus(), refund.getDecisionNote(), refund.getReasonCategory());
        return entity;
    }
}
