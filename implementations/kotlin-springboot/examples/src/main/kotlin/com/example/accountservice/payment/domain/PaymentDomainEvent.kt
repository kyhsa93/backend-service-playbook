package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * Payment Aggregate가 발행하는 Domain Event 공통 계층 — account/domain/DomainEvent.kt의
 * `sealed interface` 패턴(#241)을 Payment에도 그대로 적용한다. Account가 `MutableList<Any>`로
 * 되돌아가지 않도록, Payment의 `pullDomainEvents()`도 `List<Any>`가 아니라 `List<PaymentDomainEvent>`를
 * 반환한다.
 */
sealed interface PaymentDomainEvent {
    val paymentId: String
}

data class PaymentCompletedEvent(
    override val paymentId: String,
    val cardId: String,
    val accountId: String,
    val ownerId: String,
    val amount: Long,
    val completedAt: LocalDateTime,
) : PaymentDomainEvent

data class PaymentCancelledEvent(
    override val paymentId: String,
    val accountId: String,
    val ownerId: String,
    val amount: Long,
    val reason: String,
    val cancelledAt: LocalDateTime,
) : PaymentDomainEvent
