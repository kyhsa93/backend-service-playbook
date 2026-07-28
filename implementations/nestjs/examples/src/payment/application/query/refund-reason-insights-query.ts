import { RefundReasonInsightsResult } from '@/payment/application/query/refund-reason-insights-result'

// An ops/analytics read model, not a per-owner one — deliberately not scoped by ownerId, since
// its whole purpose is to surface refund-reason patterns across every refund, not one user's.
// This repo has no separate admin-authorization boundary, so it's exposed behind the same
// @Authenticated() baseline as every other endpoint; a production system would put this behind
// a dedicated ops/admin role instead.
export abstract class RefundReasonInsightsQuery {
  abstract getInsights(query: { fromDate?: string; toDate?: string }): Promise<RefundReasonInsightsResult>
}
