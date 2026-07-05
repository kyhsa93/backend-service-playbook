package com.example.accountservice.account.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

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
    private Money amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Transaction() {}

    static Transaction create(String accountId, TransactionType type, Money amount) {
        Transaction transaction = new Transaction();
        transaction.transactionId = UUID.randomUUID().toString();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }

    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public Money getAmount() { return amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
