package com.example.accountservice.payment.application.event

import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.RefundFindQuery
import com.example.accountservice.payment.domain.RefundRepository
import com.example.accountservice.payment.domain.RefundRequestedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reacts to [RefundRequestedEvent] (published unconditionally by
 * [com.example.accountservice.payment.domain.Refund.create], before
 * `RefundEligibilityService`'s approve/reject judgment even runs) to classify the refund's free-text
 * reason for ops-analytics reporting only — see `RefundReasonInsightsQuery`. Runs off the request hot
 * path (`RequestRefundService` never calls an LLM directly), and its result is never read back into
 * any eligibility/approval decision. Inherently idempotent: a retried delivery just re-runs the same
 * find→categorize→save cycle.
 */
@Component
class ClassifyRefundReasonEventHandler(
    private val refundReasonClassifier: RefundReasonClassifier,
    private val refundRepository: RefundRepository,
) {
    private val logger = LoggerFactory.getLogger(ClassifyRefundReasonEventHandler::class.java)

    fun handle(event: RefundRequestedEvent) {
        val (refunds, _) = refundRepository.findRefunds(RefundFindQuery(page = 0, take = 1, refundId = event.refundId))
        val refund = refunds.firstOrNull() ?: return

        val category = refundReasonClassifier.classify(event.reason)
        refund.categorizeReason(category)
        refundRepository.saveRefund(refund)

        logger.info("Refund reason classified refund_id={} category={}", event.refundId, category)
    }
}
