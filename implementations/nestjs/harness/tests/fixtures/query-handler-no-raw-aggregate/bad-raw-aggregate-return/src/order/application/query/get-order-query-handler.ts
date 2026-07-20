import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetOrderQuery } from '@/order/application/query/get-order-query'
import { Order } from '@/order/domain/order'
import { OrderRepository } from '@/order/domain/order-repository'

@QueryHandler(GetOrderQuery)
export class GetOrderQueryHandler implements IQueryHandler<GetOrderQuery> {
  constructor(private readonly orderRepository: OrderRepository) {}

  public async execute(query: GetOrderQuery): Promise<Order> {
    return this.orderRepository.findOrders({ orderId: query.orderId, take: 1, page: 0 }).then((r) => r.orders[0])
  }
}
