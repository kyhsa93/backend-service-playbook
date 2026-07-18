package com.example.accountservice.payment.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, Long> {
    fun findByPaymentId(paymentId: String): PaymentJpaEntity?

    fun findByPaymentIdAndOwnerId(
        paymentId: String,
        ownerId: String,
    ): PaymentJpaEntity?

    fun findByOwnerIdOrderByCreatedAtDesc(
        ownerId: String,
        pageable: Pageable,
    ): List<PaymentJpaEntity>

    fun countByOwnerId(ownerId: String): Long
}
