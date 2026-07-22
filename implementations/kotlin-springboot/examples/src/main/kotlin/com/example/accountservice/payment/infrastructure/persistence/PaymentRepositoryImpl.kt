package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.query.PaymentQuery
import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * The implementation that implements both the Payment write model ([PaymentRepository]) and read
 * model ([PaymentQuery]) together (the same dual-interface structure as
 * account/infrastructure/persistence/AccountRepositoryImpl).
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
        // Commits the Aggregate state (the payment row) and the Outbox row in the same transaction —
        // avoiding the dual-write problem.
        outboxWriter.saveAll(payment.pullDomainEvents())
    }

    // PaymentQuery (the read-only port) declares findPayments with exactly the same signature as
    // PaymentRepository (cqrs-pattern.md), so the findPayments override above satisfies both
    // interfaces at once.

    private fun buildJpql(
        query: PaymentFindQuery,
        count: Boolean,
    ): String {
        val select = if (count) "SELECT COUNT(p)" else "SELECT p"
        val sb = StringBuilder("$select FROM PaymentJpaEntity p WHERE 1 = 1")
        if (!query.paymentId.isNullOrBlank()) sb.append(" AND p.paymentId = :paymentId")
        if (!query.ownerId.isNullOrBlank()) sb.append(" AND p.ownerId = :ownerId")
        if (!query.cardId.isNullOrBlank()) sb.append(" AND p.cardId = :cardId")
        if (!query.status.isNullOrEmpty()) sb.append(" AND p.status IN :status")
        if (query.createdFrom != null) sb.append(" AND p.createdAt >= :createdFrom")
        if (query.createdTo != null) sb.append(" AND p.createdAt < :createdTo")
        if (!count) sb.append(" ORDER BY p.paymentId DESC")
        return sb.toString()
    }

    private fun applyParams(
        q: Query,
        query: PaymentFindQuery,
    ) {
        if (!query.paymentId.isNullOrBlank()) q.setParameter("paymentId", query.paymentId)
        if (!query.ownerId.isNullOrBlank()) q.setParameter("ownerId", query.ownerId)
        if (!query.cardId.isNullOrBlank()) q.setParameter("cardId", query.cardId)
        if (!query.status.isNullOrEmpty()) q.setParameter("status", query.status)
        if (query.createdFrom != null) q.setParameter("createdFrom", query.createdFrom)
        if (query.createdTo != null) q.setParameter("createdTo", query.createdTo)
    }
}
