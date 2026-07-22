package com.example.accountservice.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RefundTest {
    private fun createRefund(amount: Long = 500): Refund =
        Refund.create(paymentId = "payment-1", amount = amount, reason = "Simple change of mind")

    @Test
    fun `create produces a refund in the REQUESTED state`() {
        val refund = createRefund()

        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.paymentId).isEqualTo("payment-1")
        assertThat(refund.amount).isEqualTo(500)
        assertThat(refund.decisionNote).isNull()
    }

    @Test
    fun `the refund ID is a 32-character hex string without hyphens`() {
        val refund = createRefund()

        assertThat(refund.refundId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `approve transitions to APPROVED and collects a RefundApprovedEvent`() {
        val refund = createRefund()

        refund.approve("account-1", "owner-1")

        assertThat(refund.status).isEqualTo(RefundStatus.APPROVED)
        assertThat(refund.decisionNote).isEqualTo("The refund was approved.")
        val events = refund.pullDomainEvents()
        assertThat(events).hasSize(1)
        val event = events.first() as RefundApprovedEvent
        assertThat(event.accountId).isEqualTo("account-1")
        assertThat(event.ownerId).isEqualTo("owner-1")
        assertThat(event.amount).isEqualTo(500)
    }

    @Test
    fun `approving a refund that is not REQUESTED throws an exception`() {
        val refund = createRefund()
        refund.approve("account-1", "owner-1")

        assertThrows<RefundApproveRequiresRequestedRefundException> { refund.approve("account-1", "owner-1") }
    }

    @Test
    fun `reject transitions to REJECTED and stores the reason in decisionNote`() {
        val refund = createRefund()

        refund.reject("A refund can only be requested for a completed payment.")

        assertThat(refund.status).isEqualTo(RefundStatus.REJECTED)
        assertThat(refund.decisionNote).isEqualTo("A refund can only be requested for a completed payment.")
        assertThat(refund.pullDomainEvents()).isEmpty()
    }

    @Test
    fun `rejecting a refund that is not REQUESTED throws an exception`() {
        val refund = createRefund()
        refund.reject("Reason")

        assertThrows<RefundRejectRequiresRequestedRefundException> { refund.reject("Reason") }
    }

    @Test
    fun `completing an APPROVED refund transitions to COMPLETED`() {
        val refund = createRefund()
        refund.approve("account-1", "owner-1")

        refund.complete()

        assertThat(refund.status).isEqualTo(RefundStatus.COMPLETED)
    }

    @Test
    fun `completing a refund that is not APPROVED throws an exception`() {
        val refund = createRefund()

        assertThrows<RefundCompleteRequiresApprovedRefundException> { refund.complete() }
    }
}
