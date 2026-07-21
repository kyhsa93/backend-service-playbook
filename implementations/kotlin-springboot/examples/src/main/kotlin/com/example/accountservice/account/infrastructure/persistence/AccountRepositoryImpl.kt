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
    // AccountRepository(쓰기 모델) — findAccounts 하나로 목록/단건(take=1)/count를 모두 처리한다.
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
     * source/target 두 Account 저장을 하나의 물리 트랜잭션으로 묶는다 — Transfer(계좌 간 송금)가
     * 첫 사용처다. 출금 계좌 저장과 입금 계좌 저장이 각자 별도 트랜잭션으로 커밋되면 "출금은
     * 반영됐는데 입금은 유실됨" 실패 모드가 생긴다.
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

    override fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long> {
        val transactions =
            transactionJpaRepository
                .findByAccountIdOrderByCreatedAtDesc(query.accountId, PageRequest.of(query.page, query.take))
                .map(TransactionMapper::toDomain)
        val count = transactionJpaRepository.countByAccountId(query.accountId)
        return transactions to count
    }

    // AccountQuery(읽기 전용 포트)는 findAccounts/findTransactions를 AccountRepository와 정확히 같은
    // 시그니처로 선언하므로(cqrs-pattern.md), 위의 두 override가 두 인터페이스를 동시에 만족시킨다 —
    // 별도 오버로드가 필요 없다.

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
