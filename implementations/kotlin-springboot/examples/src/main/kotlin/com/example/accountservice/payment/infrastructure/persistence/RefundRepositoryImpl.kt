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
 * Refund 쓰기 모델([RefundRepository])과 읽기 모델([RefundQuery])을 함께 구현하는 구현체.
 * `findRefunds`는 두 인터페이스가 정확히 같은 시그니처로 선언하므로 하나의 override가 동시에
 * 만족시킨다(PaymentRepositoryImpl과 동일한 구조).
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
