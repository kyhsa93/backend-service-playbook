package com.example.accountservice.payment.application.query

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Queries refund-reason category counts for ops analytics. Unlike [GetRefundsService], this is
 * deliberately not scoped by requesterId/ownerId — see [RefundReasonInsightsQuery]'s KDoc for why.
 */
@Service
@Transactional(readOnly = true)
class GetRefundReasonInsightsService(
    private val refundReasonInsightsQuery: RefundReasonInsightsQuery,
) {
    fun getInsights(
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): RefundReasonInsightsResult = refundReasonInsightsQuery.getInsights(fromDate, toDate)
}
