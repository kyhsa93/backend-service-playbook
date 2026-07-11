package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountQueryRepository
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.Transaction
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
) : AccountRepository, AccountQueryRepository {

    override fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account? =
        jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)

    override fun findAll(query: AccountFindQuery): List<Account> {
        val jpql = buildJpql(query, count = false)
        return em.createQuery(jpql, Account::class.java)
            .setFirstResult(query.page * query.take)
            .setMaxResults(query.take)
            .apply { applyParams(this, query) }
            .resultList
    }

    override fun countAll(query: AccountFindQuery): Long {
        val jpql = buildJpql(query, count = true)
        return em.createQuery(jpql, Long::class.java)
            .apply { applyParams(this, query) }
            .singleResult
    }

    @Transactional
    override fun save(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
        // Aggregate 상태(account/transaction 행)와 Outbox 행을 같은 트랜잭션에 커밋한다 — 이벤트가
        // Aggregate 상태 없이 저장되거나(dual-write), 반대로 유실되는 경우가 생기지 않는다.
        outboxWriter.saveAll(account.pullDomainEvents())
    }

    @Transactional
    override fun deleteAccount(accountId: String) {
        val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
        account.markDeleted()
        jpaRepository.save(account)
    }

    override fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction> =
        transactionJpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, take))

    override fun countTransactions(accountId: String): Long =
        transactionJpaRepository.countByAccountId(accountId)

    private fun buildJpql(query: AccountFindQuery, count: Boolean): String {
        val select = if (count) "SELECT COUNT(a)" else "SELECT a"
        val sb = StringBuilder("$select FROM Account a WHERE a.deletedAt IS NULL")
        if (!query.accountId.isNullOrBlank()) sb.append(" AND a.accountId = :accountId")
        if (!query.ownerId.isNullOrBlank()) sb.append(" AND a.ownerId = :ownerId")
        if (!query.status.isNullOrEmpty()) sb.append(" AND a.status IN :status")
        if (!count) sb.append(" ORDER BY a.accountId DESC")
        return sb.toString()
    }

    private fun applyParams(q: jakarta.persistence.Query, query: AccountFindQuery) {
        if (!query.accountId.isNullOrBlank()) q.setParameter("accountId", query.accountId)
        if (!query.ownerId.isNullOrBlank()) q.setParameter("ownerId", query.ownerId)
        if (!query.status.isNullOrEmpty()) q.setParameter("status", query.status.map { AccountStatus.valueOf(it) })
    }
}
