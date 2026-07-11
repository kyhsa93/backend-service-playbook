package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
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
) : AccountRepository, AccountQuery {

    override fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account? =
        jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)
            ?.let(AccountMapper::toDomain)

    override fun findAll(query: AccountFindQuery): List<Account> {
        val jpql = buildJpql(query, count = false)
        return em.createQuery(jpql, AccountJpaEntity::class.java)
            .setFirstResult(query.page * query.take)
            .setMaxResults(query.take)
            .apply { applyParams(this, query) }
            .resultList
            .map(AccountMapper::toDomain)
    }

    override fun countAll(query: AccountFindQuery): Long {
        val jpql = buildJpql(query, count = true)
        return em.createQuery(jpql, Long::class.java)
            .apply { applyParams(this, query) }
            .singleResult
    }

    @Transactional
    override fun save(account: Account) {
        val entity = jpaRepository.findByAccountId(account.accountId)
            ?.let { AccountMapper.updateEntity(it, account) }
            ?: AccountMapper.toNewEntity(account)
        jpaRepository.save(entity)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending.map(TransactionMapper::toNewEntity))
        // Aggregate 상태(account/transaction 행)와 Outbox 행을 같은 트랜잭션에 커밋한다 — 이벤트가
        // Aggregate 상태 없이 저장되거나(dual-write), 반대로 유실되는 경우가 생기지 않는다.
        outboxWriter.saveAll(account.pullDomainEvents())
    }

    @Transactional
    override fun deleteAccount(accountId: String) {
        val entity = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
        val account = AccountMapper.toDomain(entity)
        account.markDeleted() // 도메인 메서드로 불변식(CLOSED 상태만 삭제 가능) 검증 후 deletedAt 설정
        AccountMapper.updateEntity(entity, account)
        jpaRepository.save(entity)
    }

    override fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction> =
        transactionJpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, take))
            .map(TransactionMapper::toDomain)

    override fun countTransactions(accountId: String): Long =
        transactionJpaRepository.countByAccountId(accountId)

    private fun buildJpql(query: AccountFindQuery, count: Boolean): String {
        val select = if (count) "SELECT COUNT(a)" else "SELECT a"
        val sb = StringBuilder("$select FROM AccountJpaEntity a WHERE a.deletedAt IS NULL")
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
