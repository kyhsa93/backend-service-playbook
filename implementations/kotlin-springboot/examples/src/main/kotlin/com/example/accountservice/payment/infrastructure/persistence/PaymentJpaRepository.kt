package com.example.accountservice.payment.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, Long> {
    fun findByPaymentId(paymentId: String): PaymentJpaEntity?
}
