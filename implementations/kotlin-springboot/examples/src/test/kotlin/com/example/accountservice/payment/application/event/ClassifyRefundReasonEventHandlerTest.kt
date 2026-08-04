package com.example.accountservice.payment.application.event

import com.example.accountservice.common.nowUtc
import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundReasonCategory
import com.example.accountservice.payment.domain.RefundRepository
import com.example.accountservice.payment.domain.RefundRequestedEvent
import com.example.accountservice.payment.domain.RefundStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ClassifyRefundReasonEventHandlerTest {
    private val refundReasonClassifier = mockk<RefundReasonClassifier>()
    private val refundRepository = mockk<RefundRepository>(relaxed = true)
    private val handler = ClassifyRefundReasonEventHandler(refundReasonClassifier, refundRepository)

    private val refund =
        Refund.reconstitute(
            refundId = "refund-1",
            paymentId = "payment-1",
            amount = 5000,
            reason = "The item arrived broken",
            status = RefundStatus.APPROVED,
            decisionNote = null,
            reasonCategory = null,
            createdAt = nowUtc(),
        )

    @Test
    fun `handle classifies and saves the category when the refund still exists`() {
        every { refundRepository.findRefunds(any()) } returns (listOf(refund) to 1L)
        every { refundReasonClassifier.classify("The item arrived broken") } returns RefundReasonCategory.DEFECTIVE_PRODUCT

        handler.handle(RefundRequestedEvent("refund-1", "payment-1", "The item arrived broken", nowUtc()))

        verify(exactly = 1) { refundReasonClassifier.classify("The item arrived broken") }
        verify(exactly = 1) {
            refundRepository.saveRefund(
                match { it.refundId == "refund-1" && it.reasonCategory == RefundReasonCategory.DEFECTIVE_PRODUCT },
            )
        }
    }

    @Test
    fun `handle skips classification without throwing when the refund no longer exists`() {
        every { refundRepository.findRefunds(any()) } returns (emptyList<Refund>() to 0L)

        handler.handle(RefundRequestedEvent("refund-1", "payment-1", "The item arrived broken", nowUtc()))

        verify(exactly = 0) { refundReasonClassifier.classify(any()) }
        verify(exactly = 0) { refundRepository.saveRefund(any()) }
    }
}
