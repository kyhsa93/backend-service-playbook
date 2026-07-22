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
 * The JPA mapping counterpart dedicated to payment/domain/Refund.java. The Domain Aggregate
 * (Refund) is entirely unaware of this class — the conversion is handled exclusively by
 * RefundMapper.
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

    /**
     * Applies the domain Refund's latest state to an existing row (preserving id) — used to save a
     * status/decisionNote transition.
     */
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
