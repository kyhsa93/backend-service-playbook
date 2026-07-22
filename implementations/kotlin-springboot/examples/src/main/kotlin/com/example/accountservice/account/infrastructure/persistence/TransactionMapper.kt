package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Transaction

/**
 * An object dedicated to converting between Transaction (pure domain) and TransactionJpaEntity (JPA
 * mapping). Used only inside AccountRepositoryImpl. Because a Transaction is immutable once created,
 * only an insert-only conversion is needed.
 */
internal object TransactionMapper {
    fun toDomain(entity: TransactionJpaEntity): Transaction =
        Transaction.reconstitute(
            transactionId = entity.transactionId,
            accountId = entity.accountId,
            type = entity.type,
            amount = entity.amount.toDomain(),
            referenceId = entity.referenceId,
            createdAt = entity.createdAt,
        )

    fun toNewEntity(transaction: Transaction): TransactionJpaEntity =
        TransactionJpaEntity(
            id = null,
            transactionId = transaction.transactionId,
            accountId = transaction.accountId,
            type = transaction.type,
            amount = MoneyEmbeddable.fromDomain(transaction.amount),
            referenceId = transaction.referenceId,
            createdAt = transaction.createdAt,
        )
}
