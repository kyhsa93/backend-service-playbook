package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.Transaction
import com.example.accountservice.account.domain.TransactionFindQuery
import com.example.accountservice.account.domain.TransactionType
import com.example.accountservice.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository,
    AccountQuery {
    // AccountRepository (write model) — a single findAccounts handles list/single-record (take=1)/count
    // all together.
    override fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long> {
        val jpql = buildJpql(query, count = false)
        val accounts =
            em
                .createQuery(jpql, AccountJpaEntity::class.java)
                .setFirstResult(query.page * query.take)
                .setMaxResults(query.take)
                .apply { applyParams(this, query) }
                .resultList
                .map(AccountMapper::toDomain)
        val countJpql = buildJpql(query, count = true)
        val count =
            em
                .createQuery(countJpql, Long::class.java)
                .apply { applyParams(this, query) }
                .singleResult
        return accounts to count
    }

    @Transactional
    override fun saveAccount(account: Account) {
        saveAccountInternal(account)
    }

    /**
     * Bundles the source/target Account saves into a single physical transaction — Transfer (an
     * inter-account funds transfer) is the first use of this. If the withdrawal-account save and the
     * deposit-account save were each committed in their own separate transaction, a failure mode would
     * arise where "the withdrawal is applied but the deposit is lost."
     */
    @Transactional
    override fun saveAccounts(
        source: Account,
        target: Account,
    ) {
        saveAccountInternal(source)
        saveAccountInternal(target)
    }

    private fun saveAccountInternal(account: Account) {
        val entity =
            jpaRepository
                .findByAccountId(account.accountId)
                ?.let { AccountMapper.updateEntity(it, account) }
                ?: AccountMapper.toNewEntity(account)
        jpaRepository.save(entity)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending.map(TransactionMapper::toNewEntity))
        // Commits the Aggregate state (account/transaction rows) and the Outbox row in the same
        // transaction — this prevents cases where the event is saved without the Aggregate state
        // (dual-write), or conversely gets lost.
        outboxWriter.saveAll(account.pullDomainEvents())
    }

    @Transactional
    override fun deleteAccount(accountId: String) {
        val entity = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
        val account = AccountMapper.toDomain(entity)
        account.markDeleted() // Validates the invariant (only a CLOSED account may be deleted) via the domain method, then sets deletedAt
        AccountMapper.updateEntity(entity, account)
        jpaRepository.save(entity)
    }

    override fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long> {
        val transactions =
            transactionJpaRepository
                .findByAccountIdOrderByCreatedAtDesc(query.accountId, PageRequest.of(query.page, query.take))
                .map(TransactionMapper::toDomain)
        val count = transactionJpaRepository.countByAccountId(query.accountId)
        return transactions to count
    }

    // Because AccountQuery (the read-only port) declares findAccounts/findTransactions with exactly the
    // same signatures as AccountRepository (cqrs-pattern.md), the two overrides above satisfy both
    // interfaces at once — no separate overload is needed.

    override fun hasTransactionWithReference(
        referenceId: String,
        type: TransactionType,
    ): Boolean = transactionJpaRepository.existsByReferenceIdAndType(referenceId, type)

    private fun buildJpql(
        query: AccountFindQuery,
        count: Boolean,
    ): String {
        val select = if (count) "SELECT COUNT(a)" else "SELECT a"
        val sb = StringBuilder("$select FROM AccountJpaEntity a WHERE a.deletedAt IS NULL")
        if (!query.accountId.isNullOrBlank()) sb.append(" AND a.accountId = :accountId")
        if (!query.ownerId.isNullOrBlank()) sb.append(" AND a.ownerId = :ownerId")
        if (!query.status.isNullOrEmpty()) sb.append(" AND a.status IN :status")
        if (query.excludeInterestPaidDate !=
            null
        ) {
            sb.append(" AND (a.lastInterestPaidAt IS NULL OR a.lastInterestPaidAt <> :excludeInterestPaidDate)")
        }
        if (!count) sb.append(" ORDER BY a.accountId DESC")
        return sb.toString()
    }

    private fun applyParams(
        q: jakarta.persistence.Query,
        query: AccountFindQuery,
    ) {
        if (!query.accountId.isNullOrBlank()) q.setParameter("accountId", query.accountId)
        if (!query.ownerId.isNullOrBlank()) q.setParameter("ownerId", query.ownerId)
        if (!query.status.isNullOrEmpty()) q.setParameter("status", query.status.map { AccountStatus.valueOf(it) })
        if (query.excludeInterestPaidDate != null) q.setParameter("excludeInterestPaidDate", query.excludeInterestPaidDate)
    }
}
