package com.example.accountservice.payment.application.query

import java.time.LocalDateTime

data class GetPaymentsResult(
    val payments: List<PaymentSummary>,
    val count: Long,
) {
    data class PaymentSummary(
        val paymentId: String,
        val cardId: String,
        val accountId: String,
        val amount: Long,
        val status: String,
        val createdAt: LocalDateTime,
    )
}
