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
 * payment/domain/Payment.kt의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Payment)는 이 클래스를 전혀 알지 못한다 — 변환은 PaymentMapper가 전담한다
 * (account/infrastructure/persistence/AccountJpaEntity와 동일한 구조).
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
