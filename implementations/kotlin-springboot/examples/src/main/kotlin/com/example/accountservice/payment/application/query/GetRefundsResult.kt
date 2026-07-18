package com.example.accountservice.payment.application.query

import java.time.LocalDateTime

data class GetRefundsResult(
    val refunds: List<RefundSummary>,
    val count: Long,
) {
    data class RefundSummary(
        val refundId: String,
        val paymentId: String,
        val amount: Long,
        val reason: String,
        val status: String,
        val decisionNote: String?,
        val createdAt: LocalDateTime,
    )
}
