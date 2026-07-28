package com.example.accountservice.payment.application.service

import com.example.accountservice.payment.domain.RefundReasonCategory

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the same placement/shape as [com.example.accountservice.account.application.service.TransactionAutoCategorizer],
 * just classifying a refund's free-text reason instead of a transaction's merchant name.
 * Ops-analytics input only (see `RefundReasonInsightsQuery`) — this Technical Service is never invoked
 * from, or its result ever read by, `RequestRefundService`/[com.example.accountservice.payment.domain.RefundEligibilityService].
 */
interface RefundReasonClassifier {
    fun classify(reason: String): RefundReasonCategory
}
