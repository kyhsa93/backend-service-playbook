import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetPaymentsQuery } from '@/payment/application/query/get-payments-query'
import { GetPaymentsResult } from '@/payment/application/query/payment-result'
import { PaymentQuery } from '@/payment/application/query/payment-query'

@QueryHandler(GetPaymentsQuery)
export class GetPaymentsQueryHandler implements IQueryHandler<GetPaymentsQuery, GetPaymentsResult> {
  constructor(private readonly paymentQuery: PaymentQuery) {}

  public async execute(query: GetPaymentsQuery): Promise<GetPaymentsResult> {
    return this.paymentQuery.getPayments({ ownerId: query.requesterId, page: query.page, take: query.take })
  }
}
