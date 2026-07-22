package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Transaction;

/**
 * The class dedicated to converting between Transaction (pure domain) and TransactionJpaEntity (JPA
 * mapping). It is used only inside AccountRepositoryImpl. Since a Transaction is immutable after
 * creation, only insert-side conversion is needed.
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
                transaction.getCreatedAt());
    }
}
