package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Transaction;

/**
 * The class dedicated to converting between Transaction (pure domain) and TransactionJpaEntity (JPA
 * mapping). It is used only inside AccountRepositoryImpl/TransactionRepositoryImpl.
 */
final class TransactionMapper {

    private TransactionMapper() {}

    static Transaction toDomain(TransactionJpaEntity entity) {
        return Transaction.reconstitute(
                entity.getTransactionId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount().toDomain(),
                entity.getReferenceId(),
                entity.getMerchantName(),
                entity.getCategory(),
                entity.getCreatedAt());
    }

    static TransactionJpaEntity toNewEntity(Transaction transaction) {
        return new TransactionJpaEntity(
                null,
                transaction.getTransactionId(),
                transaction.getAccountId(),
                transaction.getType(),
                MoneyEmbeddable.fromDomain(transaction.getAmount()),
                transaction.getReferenceId(),
                transaction.getMerchantName(),
                transaction.getCategory(),
                transaction.getCreatedAt());
    }

    /**
     * Applies the domain Transaction's latest state onto the existing entity (PK preserved) — used
     * by TransactionRepositoryImpl's find→modify→save cycle when {@code category} is set after the
     * fact.
     */
    static TransactionJpaEntity updateEntity(TransactionJpaEntity entity, Transaction transaction) {
        entity.applyMutableState(transaction.getCategory());
        return entity;
    }
}
