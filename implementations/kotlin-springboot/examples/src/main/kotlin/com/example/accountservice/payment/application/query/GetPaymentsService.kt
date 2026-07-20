package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.PaymentFindQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET /payments`는 인증된 요청자 ID를 암묵적으로 쓴다 — 이 저장소의 어떤 엔드포인트도 클라이언트가
 * 넘긴 ownerId를 신뢰하지 않는다(`?ownerId=` 쿼리 파라미터 없음).
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
