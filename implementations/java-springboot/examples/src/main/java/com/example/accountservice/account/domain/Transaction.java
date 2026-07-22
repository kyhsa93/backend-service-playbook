package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * A child Entity of the Account Aggregate — a pure domain object. It depends on no framework/ORM.
 * The persistence mapping is handled entirely by infrastructure/persistence/TransactionJpaEntity +
 * TransactionMapper.
 */
public class Transaction {

    private String transactionId;
    private String accountId;
    private TransactionType type;
    private Money amount;
    // An optional field allowing a transaction created as a reaction to an external BC's (Payment)
    // Integration Event to be correlated with another BC's Aggregate ID (paymentId/refundId). It is
    // absent (null) for a deposit/withdrawal directly requested by a user — it is populated only by
    // the Payment reaction Commands (WithdrawByPaymentService/DepositByPaymentService), and is
    // used,
    // together with type, as the Level 2 Ledger key that prevents duplicate processing on
    // at-least-once redelivery (see "event handler idempotency" in domain-events.md).
    private String referenceId;
    private LocalDateTime createdAt;

    private Transaction() {}

    static Transaction create(String accountId, TransactionType type, Money amount) {
        return create(accountId, type, amount, null);
    }

    static Transaction create(
            String accountId, TransactionType type, Money amount, String referenceId) {
        Transaction transaction = new Transaction();
        transaction.transactionId = IdGenerator.generate();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.referenceId = referenceId;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }

    /**
     * Used by a Repository implementation to reconstitute a Transaction from persisted data (a JPA
     * entity, etc.).
     */
    public static Transaction reconstitute(
            String transactionId,
            String accountId,
            TransactionType type,
            Money amount,
            String referenceId,
            LocalDateTime createdAt) {
        Transaction transaction = new Transaction();
        transaction.transactionId = transactionId;
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.referenceId = referenceId;
        transaction.createdAt = createdAt;
        return transaction;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public Money getAmount() {
        return amount;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
