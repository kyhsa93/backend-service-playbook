package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.domain.PaymentStatus;
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
 * The JPA mapping counterpart dedicated to payment/domain/Payment.java. The Domain Aggregate
 * (Payment) is entirely unaware of this class — the conversion is handled exclusively by
 * PaymentMapper (the same domain/JPA separation structure as
 * card/infrastructure/persistence/CardJpaEntity, see layer-architecture.md).
 */
@Entity
@Table(name = "payment")
public class PaymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String paymentId;

    @Column(nullable = false)
    private String cardId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected PaymentJpaEntity() {}

    PaymentJpaEntity(
            Long id,
            String paymentId,
            String cardId,
            String accountId,
            String ownerId,
            long amount,
            PaymentStatus status,
            LocalDateTime createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.cardId = cardId;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * Applies the domain Payment's latest state to an existing row (preserving id) — used to save a
     * status transition.
     */
    void applyMutableState(PaymentStatus status) {
        this.status = status;
    }

    Long getId() {
        return id;
    }

    String getPaymentId() {
        return paymentId;
    }

    String getCardId() {
        return cardId;
    }

    String getAccountId() {
        return accountId;
    }

    String getOwnerId() {
        return ownerId;
    }

    long getAmount() {
        return amount;
    }

    PaymentStatus getStatus() {
        return status;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
