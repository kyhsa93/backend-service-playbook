package com.example.accountservice.payment.application.query;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queries refund-reason category counts for ops analytics. Unlike {@link GetRefundsService}, this
 * is deliberately not scoped by requesterId/ownerId — see {@link RefundReasonInsightsQuery}'s
 * Javadoc for why.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetRefundReasonInsightsService {

    private final RefundReasonInsightsQuery refundReasonInsightsQuery;

    public RefundReasonInsightsResult getInsights(LocalDate fromDate, LocalDate toDate) {
        return refundReasonInsightsQuery.getInsights(fromDate, toDate);
    }
}
