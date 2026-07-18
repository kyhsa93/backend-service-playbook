import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetPaymentQuery } from '@/payment/application/query/get-payment-query'
import { GetPaymentResult } from '@/payment/application/query/payment-result'
import { PaymentQuery } from '@/payment/application/query/payment-query'

@QueryHandler(GetPaymentQuery)
export class GetPaymentQueryHandler implements IQueryHandler<GetPaymentQuery, GetPaymentResult> {
  constructor(private readonly paymentQuery: PaymentQuery) {}

  public async execute(query: GetPaymentQuery): Promise<GetPaymentResult> {
    return this.paymentQuery.getPayment({ paymentId: query.paymentId, ownerId: query.requesterId })
  }
}
