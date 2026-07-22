package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.domain.RefundStatus
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
 * The JPA-mapping-only counterpart of payment/domain/Refund.kt.
 */
@Entity
@Table(name = "refunds")
class RefundJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var refundId: String = "",
    @Column(nullable = false)
    var paymentId: String = "",
    @Column(nullable = false)
    var amount: Long = 0,
    @Column(nullable = false)
    var reason: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RefundStatus = RefundStatus.REQUESTED,
    @Column
    var decisionNote: String? = null,
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
