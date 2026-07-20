package com.example.accountservice.payment.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface RefundJpaRepository : JpaRepository<RefundJpaEntity, Long> {
    fun findByRefundId(refundId: String): RefundJpaEntity?
}
