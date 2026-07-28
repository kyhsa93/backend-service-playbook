package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Transaction
import com.example.accountservice.account.domain.TransactionRepository
import com.example.accountservice.account.domain.TransactionType
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Separate from [AccountRepositoryImpl] — see account/domain/TransactionRepository.kt for why this
 * find→modify→save cycle needs its own Repository rather than living on AccountRepository (which
 * only ever inserts Transaction rows in bulk as a side effect of saveAccount).
 */
@Repository
class TransactionRepositoryImpl(
    private val jpaRepository: TransactionJpaRepository,
) : TransactionRepository {
    override fun findTransaction(transactionId: String): Transaction? =
        jpaRepository.findByTransactionId(transactionId)?.let(TransactionMapper::toDomain)

    @Transactional
    override fun saveTransaction(transaction: Transaction) {
        val entity =
            jpaRepository
                .findByTransactionId(transaction.transactionId)
                ?.let { TransactionMapper.updateEntity(it, transaction) }
                ?: TransactionMapper.toNewEntity(transaction)
        jpaRepository.save(entity)
    }

    override fun findRecentWithdrawalAmounts(
        accountId: String,
        excludeTransactionId: String,
        limit: Int,
    ): List<Long> =
        jpaRepository
            .findByAccountIdAndTypeAndTransactionIdNotOrderByCreatedAtDesc(
                accountId,
                TransactionType.WITHDRAWAL,
                excludeTransactionId,
                PageRequest.of(0, limit),
            ).map { it.amount.amount }
}
