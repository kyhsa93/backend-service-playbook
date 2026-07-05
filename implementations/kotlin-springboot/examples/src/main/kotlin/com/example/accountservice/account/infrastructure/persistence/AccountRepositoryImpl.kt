package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.Transaction
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val em: EntityManager,
) : AccountRepository {

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

    override fun save(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
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
