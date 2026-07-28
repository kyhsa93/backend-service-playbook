package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * The common Domain Event layer published by the Refund Aggregate — uses a `sealed interface` for the
 * same reason as [PaymentDomainEvent]. Having a Refund-specific layer means any events added in the
 * future still get `when`-branch exhaustiveness checking.
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

/**
 * Published unconditionally by [Refund.create] — before [RefundEligibilityService]'s approve/reject
 * judgment even runs. [com.example.accountservice.payment.application.event.ClassifyRefundReasonEventHandler]
 * reacts to this to build ops-analytics insight from every refund's stated reason, independent of
 * whether the refund is ultimately approved or rejected (a rejected refund's reason is just as useful a
 * signal for the ops dashboard as an approved one's).
 */
data class RefundRequestedEvent(
    override val refundId: String,
    val paymentId: String,
    val reason: String,
    val createdAt: LocalDateTime,
) : RefundDomainEvent
