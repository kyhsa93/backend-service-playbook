package com.example.accountservice.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * RefundEligibilityService(Domain Service) 단위 테스트 — Application 레이어를 거치지 않고 이
 * 클래스를 직접 `RefundEligibilityService()`로 인스턴스화해 판단 로직만 검증한다
 * (domain-service.md "실제 동작하는 예시" 참고).
 */
class RefundEligibilityServiceTest {
    private val service = RefundEligibilityService()

    private fun completedPayment(amount: Long = 1000): Payment {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = amount)
        payment.complete()
        return payment
    }

    @Test
    fun `원 결제가 COMPLETED이고 환불 금액이 결제 금액 이하면 승인된다`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "단순 변심")

        val decision = service.evaluate(payment, refund)

        assertThat(decision.approved).isTrue()
        assertThat(decision.reason).isNull()
    }

    @Test
    fun `환불 금액이 결제 금액과 같아도 승인된다`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 1000, reason = "전액 환불")

        val decision = service.evaluate(payment, refund)

        assertThat(decision.approved).isTrue()
    }

    @Test
    fun `원 결제가 COMPLETED가 아니면(PENDING) 거부된다`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "단순 변심")

        val decision = service.evaluate(payment, refund)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
    }

    @Test
    fun `원 결제가 CANCELLED면 거부된다`() {
        val payment = completedPayment(1000)
        payment.cancel("고객 요청")
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "단순 변심")

        val decision = service.evaluate(payment, refund)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
    }

    @Test
    fun `환불 금액이 결제 금액을 초과하면 거부된다`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 1001, reason = "단순 변심")

        val decision = service.evaluate(payment, refund)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("환불 금액은 결제 금액을 초과할 수 없습니다.")
    }
}
