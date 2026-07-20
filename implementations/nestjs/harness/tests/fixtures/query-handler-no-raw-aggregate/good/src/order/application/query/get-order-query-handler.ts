import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetOrderQuery } from '@/order/application/query/get-order-query'
import { GetOrderResult } from '@/order/application/query/order-result'

@QueryHandler(GetOrderQuery)
export class GetOrderQueryHandler implements IQueryHandler<GetOrderQuery, GetOrderResult> {
  public async execute(query: GetOrderQuery): Promise<GetOrderResult> {
    return { orderId: query.orderId } as GetOrderResult
  }
}
