package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.domain.PaymentStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * The JPA-mapping-only counterpart of payment/domain/Payment.kt.
 * The Domain Aggregate (Payment) knows nothing about this class at all — the conversion is handled
 * exclusively by PaymentMapper (the same structure as account/infrastructure/persistence/AccountJpaEntity).
 */
@Entity
@Table(name = "payments")
class PaymentJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var paymentId: String = "",
    @Column(nullable = false)
    var cardId: String = "",
    @Column(nullable = false)
    var accountId: String = "",
    @Column(nullable = false)
    var ownerId: String = "",
    @Column(nullable = false)
    var amount: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus = PaymentStatus.PENDING,
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
