package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * The common Domain Event layer published by the Refund Aggregate — uses a `sealed interface` for the
 * same reason as [PaymentDomainEvent]. There is currently only one, [RefundApprovedEvent], but having
 * a Refund-specific layer means any events added in the future still get `when`-branch exhaustiveness
 * checking.
 */
sealed interface RefundDomainEvent {
    val refundId: String
}

data class RefundApprovedEvent(
    override val refundId: String,
    val paymentId: String,
    val accountId: String,
    val ownerId: String,
    val amount: Long,
    val approvedAt: LocalDateTime,
) : RefundDomainEvent
