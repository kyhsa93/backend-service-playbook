package com.example.accountservice.payment.domain

/**
 * The feature vector [com.example.accountservice.payment.application.service.RefundFraudRiskScorer]
 * scores. Assembled by the Application layer from the requester's refund history
 * ([RefundRepository.summarizeRefundsByOwner]) plus the current Payment/Refund pair — never by
 * [RefundEligibilityService] or the scorer implementation itself, so both stay decoupled from how
 * history is stored/queried.
 */
data class RefundRiskFeatures(
    val refundCountLast30Days: Int,
    val rejectedRefundCountLast30Days: Int,
    val refundToPaymentAmountRatio: Double,
    val minutesSincePayment: Double,
)
