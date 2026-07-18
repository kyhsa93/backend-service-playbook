package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.query.PaymentQuery
import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Payment 쓰기 모델([PaymentRepository])과 읽기 모델([PaymentQuery])을 함께 구현하는 구현체
 * (account/infrastructure/persistence/AccountRepositoryImpl과 동일한 이중 인터페이스 구조).
 */
@Repository
class PaymentRepositoryImpl(
    private val jpaRepository: PaymentJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : PaymentRepository,
    PaymentQuery {
    override fun findPayments(query: PaymentFindQuery): Pair<List<Payment>, Long> {
        val jpql = buildJpql(query, count = false)
        val payments =
            em
                .createQuery(jpql, PaymentJpaEntity::class.java)
                .setFirstResult(query.page * query.take)
                .setMaxResults(query.take)
                .apply { applyParams(this, query) }
                .resultList
                .map(PaymentMapper::toDomain)
        val countJpql = buildJpql(query, count = true)
        val count =
            em
                .createQuery(countJpql, Long::class.java)
                .apply { applyParams(this, query) }
                .singleResult
        return payments to count
    }

    @Transactional
    override fun savePayment(payment: Payment) {
        val entity =
            jpaRepository
                .findByPaymentId(payment.paymentId)
                ?.let { PaymentMapper.updateEntity(it, payment) }
                ?: PaymentMapper.toNewEntity(payment)
        jpaRepository.save(entity)
        // Aggregate 상태(payment 행)와 Outbox 행을 같은 트랜잭션에 커밋한다 — dual-write 문제 회피.
        outboxWriter.saveAll(payment.pullDomainEvents())
    }

    override fun findByPaymentIdAndOwnerId(
        paymentId: String,
        ownerId: String,
    ): Payment? = jpaRepository.findByPaymentIdAndOwnerId(paymentId, ownerId)?.let(PaymentMapper::toDomain)

    override fun findByOwnerId(
        ownerId: String,
        page: Int,
        take: Int,
    ): List<Payment> =
        jpaRepository
            .findByOwnerIdOrderByCreatedAtDesc(ownerId, PageRequest.of(page, take))
            .map(PaymentMapper::toDomain)

    override fun countByOwnerId(ownerId: String): Long = jpaRepository.countByOwnerId(ownerId)

    private fun buildJpql(
        query: PaymentFindQuery,
        count: Boolean,
    ): String {
        val select = if (count) "SELECT COUNT(p)" else "SELECT p"
        val sb = StringBuilder("$select FROM PaymentJpaEntity p WHERE 1 = 1")
        if (!query.paymentId.isNullOrBlank()) sb.append(" AND p.paymentId = :paymentId")
        if (!query.ownerId.isNullOrBlank()) sb.append(" AND p.ownerId = :ownerId")
        if (!count) sb.append(" ORDER BY p.paymentId DESC")
        return sb.toString()
    }

    private fun applyParams(
        q: Query,
        query: PaymentFindQuery,
    ) {
        if (!query.paymentId.isNullOrBlank()) q.setParameter("paymentId", query.paymentId)
        if (!query.ownerId.isNullOrBlank()) q.setParameter("ownerId", query.ownerId)
    }
}
