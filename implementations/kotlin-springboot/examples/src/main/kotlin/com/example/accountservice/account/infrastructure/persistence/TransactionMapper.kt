package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Transaction

/**
 * An object dedicated to converting between Transaction (pure domain) and TransactionJpaEntity (JPA
 * mapping). Used by AccountRepositoryImpl (insert-only, as a side effect of saveAccount) and
 * TransactionRepositoryImpl (the find→categorize→save cycle, the one legitimate in-place update — see
 * domain/TransactionRepository.kt).
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
            merchantName = entity.merchantName,
            category = entity.category,
        )

    fun toNewEntity(transaction: Transaction): TransactionJpaEntity =
        TransactionJpaEntity(
            id = null,
            transactionId = transaction.transactionId,
            accountId = transaction.accountId,
            type = transaction.type,
            amount = MoneyEmbeddable.fromDomain(transaction.amount),
            referenceId = transaction.referenceId,
            merchantName = transaction.merchantName,
            category = transaction.category,
            createdAt = transaction.createdAt,
        )

    // Only ever changes `category` in practice (TransactionRepositoryImpl.saveTransaction) — every
    // other field is set once at creation and never legitimately changes afterward, but this mirrors
    // AccountMapper.updateEntity's shape (write every domain field back onto the existing row) rather
    // than special-casing just the one column.
    fun updateEntity(
        entity: TransactionJpaEntity,
        transaction: Transaction,
    ): TransactionJpaEntity =
        entity.apply {
            accountId = transaction.accountId
            type = transaction.type
            amount = MoneyEmbeddable.fromDomain(transaction.amount)
            referenceId = transaction.referenceId
            merchantName = transaction.merchantName
            category = transaction.category
            createdAt = transaction.createdAt
        }
}
