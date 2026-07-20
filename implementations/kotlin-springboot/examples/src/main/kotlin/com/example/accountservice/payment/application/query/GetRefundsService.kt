package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.RefundFindQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Refund 테이블은 ownerId를 갖지 않으므로, Payment를 `paymentId` + `ownerId`로 먼저 조회해 소유권을
 * 확인한 뒤에만 그 결제에 대한 환불 내역을 조회한다(account의 `getTransactions`가 계좌 소유권을
 * 먼저 확인하는 것과 동일한 패턴).
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
