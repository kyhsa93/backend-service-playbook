import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetRefundReasonInsightsQuery } from '@/payment/application/query/get-refund-reason-insights-query'
import { RefundReasonInsightsQuery } from '@/payment/application/query/refund-reason-insights-query'
import { RefundReasonInsightsResult } from '@/payment/application/query/refund-reason-insights-result'

@QueryHandler(GetRefundReasonInsightsQuery)
export class GetRefundReasonInsightsQueryHandler implements IQueryHandler<GetRefundReasonInsightsQuery, RefundReasonInsightsResult> {
  constructor(private readonly refundReasonInsightsQuery: RefundReasonInsightsQuery) {}

  public async execute(query: GetRefundReasonInsightsQuery): Promise<RefundReasonInsightsResult> {
    return this.refundReasonInsightsQuery.getInsights({ fromDate: query.fromDate, toDate: query.toDate })
  }
}
