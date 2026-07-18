package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.domain.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * payment/domain/Refund.java의 JPA 매핑 전용 대응물. Domain Aggregate(Refund)는 이 클래스를 전혀 알지 못한다 — 변환은
 * RefundMapper가 전담한다.
 */
@Entity
@Table(name = "refund")
public class RefundJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String refundId;

    @Column(nullable = false)
    private String paymentId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column private String decisionNote;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected RefundJpaEntity() {}

    RefundJpaEntity(
            Long id,
            String refundId,
            String paymentId,
            long amount,
            String reason,
            RefundStatus status,
            String decisionNote,
            LocalDateTime createdAt) {
        this.id = id;
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.decisionNote = decisionNote;
        this.createdAt = createdAt;
    }

    /** 기존 row(id 보존)에 도메인 Refund의 최신 상태를 반영한다 — status/decisionNote 전이 저장에 사용. */
    void applyMutableState(RefundStatus status, String decisionNote) {
        this.status = status;
        this.decisionNote = decisionNote;
    }

    Long getId() {
        return id;
    }

    String getRefundId() {
        return refundId;
    }

    String getPaymentId() {
        return paymentId;
    }

    long getAmount() {
        return amount;
    }

    String getReason() {
        return reason;
    }

    RefundStatus getStatus() {
        return status;
    }

    String getDecisionNote() {
        return decisionNote;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
