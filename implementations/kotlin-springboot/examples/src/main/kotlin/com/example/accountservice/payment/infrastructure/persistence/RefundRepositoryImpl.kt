package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.query.RefundQuery
import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Refund 쓰기 모델([RefundRepository])과 읽기 모델([RefundQuery])을 함께 구현하는 구현체.
 */
@Repository
class RefundRepositoryImpl(
    private val jpaRepository: RefundJpaRepository,
    private val outboxWriter: OutboxWriter,
) : RefundRepository,
    RefundQuery {
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

    override fun findByPaymentId(
        paymentId: String,
        page: Int,
        take: Int,
    ): List<Refund> =
        jpaRepository
            .findByPaymentIdOrderByCreatedAtDesc(paymentId, PageRequest.of(page, take))
            .map(RefundMapper::toDomain)

    override fun countByPaymentId(paymentId: String): Long = jpaRepository.countByPaymentId(paymentId)
}
