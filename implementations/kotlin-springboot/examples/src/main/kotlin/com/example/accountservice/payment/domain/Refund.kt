package com.example.accountservice.payment.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Refund Aggregate Root — a plain Kotlin object with no dependency on any framework/ORM.
 *
 * Refund cannot make judgments about the original payment's (Payment's) status/amount itself —
 * [approve]/[reject] are called with the result ([RefundDecision]) that [RefundEligibilityService]
 * (a Domain Service) produces by loading both the Payment and Refund Aggregates together and
 * coordinating between them, passed in from the Application layer.
 */
class Refund private constructor() {
    var refundId: String = ""
        private set

    var paymentId: String = ""
        private set

    var amount: Long = 0
        private set

    var reason: String = ""
        private set

    var status: RefundStatus = RefundStatus.REQUESTED
        private set

    var decisionNote: String? = null
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    private val domainEvents: MutableList<RefundDomainEvent> = mutableListOf()

    companion object {
        fun create(
            paymentId: String,
            amount: Long,
            reason: String,
        ): Refund =
            Refund().apply {
                this.refundId = generateId()
                this.paymentId = paymentId
                this.amount = amount
                this.reason = reason
                this.status = RefundStatus.REQUESTED
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Used by the Repository implementation to restore a Refund from persisted data (a JPA entity, etc).
         */
        fun reconstitute(
            refundId: String,
            paymentId: String,
            amount: Long,
            reason: String,
            status: RefundStatus,
            decisionNote: String?,
            createdAt: LocalDateTime,
        ): Refund =
            Refund().apply {
                this.refundId = refundId
                this.paymentId = paymentId
                this.amount = amount
                this.reason = reason
                this.status = status
                this.decisionNote = decisionNote
                this.createdAt = createdAt
            }
    }

    /**
     * [accountId]/[ownerId] are not part of RefundEligibilityService's judgment — they are just
     * reference data passed in from the Payment the Application layer already loaded, to assemble the
     * Integration Event that will propagate to external BCs after the judgment (Refund does not keep
     * them as its own fields at all times).
     */
    fun approve(
        accountId: String,
        ownerId: String,
    ) {
        if (status != RefundStatus.REQUESTED) throw RefundApproveRequiresRequestedRefundException()
        status = RefundStatus.APPROVED
        decisionNote = "The refund was approved."
        domainEvents += RefundApprovedEvent(refundId, paymentId, accountId, ownerId, amount, LocalDateTime.now())
    }

    fun reject(reason: String) {
        if (status != RefundStatus.REQUESTED) throw RefundRejectRequiresRequestedRefundException()
        status = RefundStatus.REJECTED
        decisionNote = reason
    }

    /**
     * Currently, refund processing ends once the Account BC subscribes to refund.approved.v1 and
     * executes the credit — there is no callback path that reports that credit's success back to the
     * Payment BC (it isn't on the REST surface). The method is kept for the sake of a complete state
     * model in the Payment domain (verified by a Domain unit test), but no Command currently calls it
     * — it is unwired for the same reason as [Payment.fail].
     */
    fun complete() {
        if (status != RefundStatus.APPROVED) throw RefundCompleteRequiresApprovedRefundException()
        status = RefundStatus.COMPLETED
    }

    fun pullDomainEvents(): List<RefundDomainEvent> = domainEvents.toList().also { domainEvents.clear() }
}
