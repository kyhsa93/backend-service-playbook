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
 * The JPA-mapping counterpart of account/domain/Transaction.java. The Domain child Entity
 * (Transaction) has no knowledge of this class at all — the conversion is handled entirely by
 * TransactionMapper.
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

    // A correlation key populated only by Payment BC reactions
    // (withdraw-by-payment/deposit-by-payment)
    // — used for idempotency checks (do not reprocess if a transaction with the same
    // referenceId+type
    // already exists).
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
