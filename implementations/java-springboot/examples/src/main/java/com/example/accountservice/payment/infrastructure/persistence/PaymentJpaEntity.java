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
 * payment/domain/Payment.java의 JPA 매핑 전용 대응물. Domain Aggregate(Payment)는 이 클래스를 전혀 알지 못한다 — 변환은
 * PaymentMapper가 전담한다(card/infrastructure/persistence/CardJpaEntity와 동일한 domain/JPA 분리 구조,
 * layer-architecture.md 참고).
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

    /** 기존 row(id 보존)에 도메인 Payment의 최신 상태를 반영한다 — status 전이 저장에 사용. */
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
