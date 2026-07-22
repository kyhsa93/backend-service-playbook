package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.RefundFindQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The Refund table does not have an ownerId, so it queries Payment by `paymentId` + `ownerId` first to
 * verify ownership, and only then queries that payment's refund history (the same pattern as
 * account's `getTransactions` verifying account ownership first).
 */
@Service
@Transactional(readOnly = true)
class GetRefundsService(
    private val paymentQuery: PaymentQuery,
    private val refundQuery: RefundQuery,
) {
    fun getRefunds(
        paymentId: String,
        requesterId: String,
        page: Int,
        take: Int,
    ): GetRefundsResult {
        val (payments, _) =
            paymentQuery.findPayments(
                PaymentFindQuery(page = 0, take = 1, paymentId = paymentId, ownerId = requesterId),
            )
        payments.firstOrNull() ?: throw PaymentNotFoundException(paymentId)

        val (refunds, count) =
            refundQuery.findRefunds(RefundFindQuery(page = page, take = take, paymentId = paymentId))
        return GetRefundsResult(
            refunds =
                refunds.map {
                    GetRefundsResult.RefundSummary(
                        refundId = it.refundId,
                        paymentId = it.paymentId,
                        amount = it.amount,
                        reason = it.reason,
                        status = it.status.name,
                        decisionNote = it.decisionNote,
                        createdAt = it.createdAt,
                    )
                },
            count = count,
        )
    }
}
