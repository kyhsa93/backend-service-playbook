package com.example.accountservice.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RefundTest {
    private fun createRefund(amount: Long = 500): Refund = Refund.create(paymentId = "payment-1", amount = amount, reason = "단순 변심")

    @Test
    fun `create 하면 REQUESTED 상태의 환불이 생성된다`() {
        val refund = createRefund()

        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.paymentId).isEqualTo("payment-1")
        assertThat(refund.amount).isEqualTo(500)
        assertThat(refund.decisionNote).isNull()
    }

    @Test
    fun `환불 ID는 하이픈 없는 32자리 hex 문자열이다`() {
        val refund = createRefund()

        assertThat(refund.refundId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `approve 하면 APPROVED 상태가 되고 RefundApprovedEvent가 수집된다`() {
        val refund = createRefund()

        refund.approve("account-1", "owner-1")

        assertThat(refund.status).isEqualTo(RefundStatus.APPROVED)
        assertThat(refund.decisionNote).isEqualTo("환불이 승인되었습니다.")
        val events = refund.pullDomainEvents()
        assertThat(events).hasSize(1)
        val event = events.first() as RefundApprovedEvent
        assertThat(event.accountId).isEqualTo("account-1")
        assertThat(event.ownerId).isEqualTo("owner-1")
        assertThat(event.amount).isEqualTo(500)
    }

    @Test
    fun `REQUESTED가 아닌 환불을 approve 하면 예외를 던진다`() {
        val refund = createRefund()
        refund.approve("account-1", "owner-1")

        assertThrows<RefundApproveRequiresRequestedRefundException> { refund.approve("account-1", "owner-1") }
    }

    @Test
    fun `reject 하면 REJECTED 상태가 되고 사유가 decisionNote에 저장된다`() {
        val refund = createRefund()

        refund.reject("완료된 결제에 대해서만 환불을 요청할 수 있습니다.")

        assertThat(refund.status).isEqualTo(RefundStatus.REJECTED)
        assertThat(refund.decisionNote).isEqualTo("완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
        assertThat(refund.pullDomainEvents()).isEmpty()
    }

    @Test
    fun `REQUESTED가 아닌 환불을 reject 하면 예외를 던진다`() {
        val refund = createRefund()
        refund.reject("사유")

        assertThrows<RefundRejectRequiresRequestedRefundException> { refund.reject("사유") }
    }

    @Test
    fun `APPROVED 환불을 complete 하면 COMPLETED 상태가 된다`() {
        val refund = createRefund()
        refund.approve("account-1", "owner-1")

        refund.complete()

        assertThat(refund.status).isEqualTo(RefundStatus.COMPLETED)
    }

    @Test
    fun `APPROVED가 아닌 환불을 complete 하면 예외를 던진다`() {
        val refund = createRefund()

        assertThrows<RefundCompleteRequiresApprovedRefundException> { refund.complete() }
    }
}
