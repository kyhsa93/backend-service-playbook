package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * The common Domain Event layer published by the Payment Aggregate — applies the same
 * `sealed interface` pattern (#241) from account/domain/DomainEvent.kt to Payment as well. To keep
 * this from regressing into `MutableList<Any>` the way Account's did, Payment's
 * `pullDomainEvents()` also returns `List<PaymentDomainEvent>` rather than `List<Any>`.
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
