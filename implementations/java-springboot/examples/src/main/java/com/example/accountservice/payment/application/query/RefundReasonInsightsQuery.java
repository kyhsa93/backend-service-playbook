package com.example.accountservice.payment.application.query;

import java.time.LocalDate;

/**
 * An ops/analytics read model, not a per-owner one — deliberately not scoped by ownerId, since its
 * whole purpose is to surface refund-reason patterns across every refund, not one user's. This repo
 * has no separate admin-authorization boundary, so it's exposed behind the same baseline JWT
 * authentication as every other endpoint; a production system would put this behind a dedicated
 * ops/admin role instead.
 */
public interface RefundReasonInsightsQuery {

    RefundReasonInsightsResult getInsights(LocalDate fromDate, LocalDate toDate);
}
