package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.PaymentFindQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET /payments` implicitly uses the authenticated requester ID — no endpoint in this repository
 * trusts a client-supplied ownerId (there is no `?ownerId=` query parameter).
 */
@Service
@Transactional(readOnly = true)
class GetPaymentsService(
    private val paymentQuery: PaymentQuery,
) {
    fun getPayments(
        requesterId: String,
        page: Int,
        take: Int,
    ): GetPaymentsResult {
        val (payments, count) =
            paymentQuery.findPayments(PaymentFindQuery(page = page, take = take, ownerId = requesterId))
        return GetPaymentsResult(
            payments =
                payments.map {
                    GetPaymentsResult.PaymentSummary(
                        paymentId = it.paymentId,
                        cardId = it.cardId,
                        accountId = it.accountId,
                        amount = it.amount,
                        status = it.status.name,
                        createdAt = it.createdAt,
                    )
                },
            count = count,
        )
    }
}
