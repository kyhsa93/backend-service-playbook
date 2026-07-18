package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Transaction

/**
 * Transaction(순수 도메인) ↔ TransactionJpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * AccountRepositoryImpl 내부에서만 사용된다. Transaction은 생성 후 불변이므로 insert 전용 변환만 필요하다.
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
