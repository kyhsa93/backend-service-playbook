package com.example.accountservice.card.application.adapter

import java.time.LocalDateTime

/**
 * Adapter interface (Anticorruption Layer) for synchronously querying the Payment BC.
 *
 * Used by the monthly card-usage-statement (SendMonthlyCardStatementsService) to aggregate the
 * payment count/total per card — this reuses the same pattern the Card BC already uses to query
 * Account ([com.example.accountservice.card.application.adapter.AccountAdapter]). The return type
 * does not expose the Payment BC's `Payment`/`PaymentStatus`; it is translated into the minimal form
 * the Card BC needs ([PaymentSummaryView]). The actual translation is handled by
 * [com.example.accountservice.card.infrastructure.CardPaymentAdapterImpl].
 */
interface PaymentAdapter {
    fun summarizePayments(
        cardId: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): PaymentSummaryView
}

data class PaymentSummaryView(
    val count: Long,
    val totalAmount: Long,
)
