package com.example.accountservice.card.infrastructure

import com.example.accountservice.card.application.adapter.PaymentAdapter
import com.example.accountservice.card.application.adapter.PaymentSummaryView
import com.example.accountservice.payment.application.query.PaymentQuery
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentStatus
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Implementation of [PaymentAdapter] (ACL). Injects and calls the read port ([PaymentQuery]) exposed
 * by the Payment BC, and translates the Payment BC's model into the minimal form the Card BC uses
 * ([PaymentSummaryView]). Only completed (COMPLETED) payments are aggregated as "usage" —
 * PENDING/FAILED/CANCELLED payments were never actually charged or were cancelled, so they are
 * excluded from the statement.
 *
 * Reason for the `Card` prefix on the class name: the same convention as
 * [com.example.accountservice.payment.infrastructure.PaymentAccountAdapterImpl]/
 * [com.example.accountservice.payment.infrastructure.PaymentCardAdapterImpl] — since Spring's default
 * bean-name generation looks only at the simple class name regardless of package, prefixing with the
 * consuming BC's name preemptively avoids future collisions with other `PaymentAdapterImpl`s.
 *
 * The actual query simply sums the list returned by `findPayments` — since the statement's target
 * period (the last 30 days, up to [MAX_PAYMENTS_PER_STATEMENT] records) is an internal batch
 * aggregation and not subject to REST pagination, no separate summary-only query is added; the
 * existing `findPayments` pattern is reused as-is (the same judgment as card's
 * CancelCardsByAccountService giving `take` a sufficiently large value).
 */
@Component
class CardPaymentAdapterImpl(
    private val paymentQuery: PaymentQuery,
) : PaymentAdapter {
    override fun summarizePayments(
        cardId: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): PaymentSummaryView {
        val (payments, count) =
            paymentQuery.findPayments(
                PaymentFindQuery(
                    page = 0,
                    take = MAX_PAYMENTS_PER_STATEMENT,
                    cardId = cardId,
                    status = listOf(PaymentStatus.COMPLETED),
                    createdFrom = from,
                    createdTo = to,
                ),
            )
        return PaymentSummaryView(count = count, totalAmount = payments.sumOf { it.amount })
    }

    companion object {
        private const val MAX_PAYMENTS_PER_STATEMENT = 10_000
    }
}
