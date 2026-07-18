package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * account/domain/Transaction.java의 JPA 매핑 전용 대응물. Domain 하위 Entity(Transaction)는 이 클래스를 전혀 알지 못한다 —
 * 변환은 TransactionMapper가 전담한다.
 */
@Entity
@Table(name = "transactions")
public class TransactionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;

    @Column(nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Embedded private MoneyEmbeddable amount;

    // Payment BC 반응(withdraw-by-payment/deposit-by-payment)에서만 채워지는 상관관계 키 — 멱등성 판단(같은
    // referenceId+type의 거래가 이미 있으면 재처리하지 않음)에 쓰인다.
    @Column(nullable = true)
    private String referenceId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected TransactionJpaEntity() {}

    TransactionJpaEntity(
            Long id,
            String transactionId,
            String accountId,
            TransactionType type,
            MoneyEmbeddable amount,
            String referenceId,
            LocalDateTime createdAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getTransactionId() {
        return transactionId;
    }

    String getAccountId() {
        return accountId;
    }

    TransactionType getType() {
        return type;
    }

    MoneyEmbeddable getAmount() {
        return amount;
    }

    String getReferenceId() {
        return referenceId;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
