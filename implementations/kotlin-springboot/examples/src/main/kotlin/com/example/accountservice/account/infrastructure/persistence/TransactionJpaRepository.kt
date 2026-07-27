package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.TransactionType
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionJpaRepository : JpaRepository<TransactionJpaEntity, Long> {
    // findTransactions (AccountRepositoryImpl) now builds its own JPQL via EntityManager to support
    // the optional type/fromDate/toDate filters, so the derived list/count query methods that used
    // to live here (findByAccountIdOrderByCreatedAtDesc/countByAccountId) were removed.

    fun existsByReferenceIdAndType(
        referenceId: String,
        type: TransactionType,
    ): Boolean

    // Used only by TaskQueueE2ETest to backdate a transaction's createdAt into "last month" — the
    // domain has no legitimate use case for changing a Transaction's timestamp after the fact.
    fun findByTransactionId(transactionId: String): TransactionJpaEntity?
}
