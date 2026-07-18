import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetRefundsQuery } from '@/payment/application/query/get-refunds-query'
import { GetRefundsResult } from '@/payment/application/query/refund-result'
import { RefundQuery } from '@/payment/application/query/refund-query'

@QueryHandler(GetRefundsQuery)
export class GetRefundsQueryHandler implements IQueryHandler<GetRefundsQuery, GetRefundsResult> {
  constructor(private readonly refundQuery: RefundQuery) {}

  public async execute(query: GetRefundsQuery): Promise<GetRefundsResult> {
    return this.refundQuery.getRefunds({
      paymentId: query.paymentId,
      ownerId: query.requesterId,
      page: query.page,
      take: query.take
    })
  }
}
