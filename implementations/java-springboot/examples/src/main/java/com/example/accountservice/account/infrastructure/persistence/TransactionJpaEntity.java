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
 * account/domain/Transaction.java의 JPA 매핑 전용 대응물.
 * Domain 하위 Entity(Transaction)는 이 클래스를 전혀 알지 못한다 — 변환은 TransactionMapper가 전담한다.
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

    @Embedded
    private MoneyEmbeddable amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected TransactionJpaEntity() {}

    TransactionJpaEntity(
            Long id,
            String transactionId,
            String accountId,
            TransactionType type,
            MoneyEmbeddable amount,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    String getTransactionId() { return transactionId; }
    String getAccountId() { return accountId; }
    TransactionType getType() { return type; }
    MoneyEmbeddable getAmount() { return amount; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
