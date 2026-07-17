package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * Account Aggregate의 하위 Entity — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다. 영속성 매핑은
 * infrastructure/persistence/TransactionJpaEntity + TransactionMapper가 전담한다.
 */
public class Transaction {

    private String transactionId;
    private String accountId;
    private TransactionType type;
    private Money amount;
    private LocalDateTime createdAt;

    private Transaction() {}

    static Transaction create(String accountId, TransactionType type, Money amount) {
        Transaction transaction = new Transaction();
        transaction.transactionId = IdGenerator.generate();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }

    /** Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Transaction을 복원할 때 사용한다. */
    public static Transaction reconstitute(
            String transactionId,
            String accountId,
            TransactionType type,
            Money amount,
            LocalDateTime createdAt) {
        Transaction transaction = new Transaction();
        transaction.transactionId = transactionId;
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
