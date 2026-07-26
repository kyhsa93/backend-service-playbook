package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.query.RefundQuery
import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundFindQuery
import com.example.accountservice.payment.domain.RefundRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * The implementation that implements both the Refund write model ([RefundRepository]) and read model
 * ([RefundQuery]) together. Both interfaces declare `findRefunds` with exactly the same signature, so
 * a single override satisfies both at once (the same structure as PaymentRepositoryImpl).
 */
@Repository
class RefundRepositoryImpl(
    private val jpaRepository: RefundJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : RefundRepository,
    RefundQuery {
    override fun findRefunds(query: RefundFindQuery): Pair<List<Refund>, Long> {
        val jpql = buildJpql(query, count = false)
        val refunds =
            em
                .createQuery(jpql, RefundJpaEntity::class.java)
                .setFirstResult(query.page * query.take)
                .setMaxResults(query.take)
                .apply { applyParams(this, query) }
                .resultList
                .map(RefundMapper::toDomain)
        val countJpql = buildJpql(query, count = true)
        val count =
            em
                .createQuery(countJpql, Long::class.java)
                .apply { applyParams(this, query) }
                .singleResult
        return refunds to count
    }

    @Transactional
    override fun saveRefund(refund: Refund) {
        val entity =
            jpaRepository
                .findByRefundId(refund.refundId)
                ?.let { RefundMapper.updateEntity(it, refund) }
                ?: RefundMapper.toNewEntity(refund)
        jpaRepository.save(entity)
        outboxWriter.saveAll(refund.pullDomainEvents())
    }

    private fun buildJpql(
        query: RefundFindQuery,
        count: Boolean,
    ): String {
        val select = if (count) "SELECT COUNT(r)" else "SELECT r"
        val sb = StringBuilder("$select FROM RefundJpaEntity r WHERE 1 = 1")
        if (!query.refundId.isNullOrBlank()) sb.append(" AND r.refundId = :refundId")
        if (!query.paymentId.isNullOrBlank()) sb.append(" AND r.paymentId = :paymentId")
        if (!count) sb.append(" ORDER BY r.refundId DESC")
        return sb.toString()
    }

    private fun applyParams(
        q: Query,
        query: RefundFindQuery,
    ) {
        if (!query.refundId.isNullOrBlank()) q.setParameter("refundId", query.refundId)
        if (!query.paymentId.isNullOrBlank()) q.setParameter("paymentId", query.paymentId)
    }
}
