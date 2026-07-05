import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { GetOrderResult } from '@/order/application/query/get-order-result'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'
import { OrderQuery } from '@/order/application/query/order-query'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@Injectable()
export class OrderQueryImpl extends OrderQuery {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderItemEntity) private readonly orderItemRepo: Repository<OrderItemEntity>
  ) {
    super()
  }

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    const qb = this.orderRepo.createQueryBuilder('order')
      .orderBy('order.orderId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.status?.length) qb.andWhere('order.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      orders: rows.map((o) => ({ orderId: o.orderId, description: null, status: o.status })),
      totalCount: count
    }
  }

  public async getOrder(param: { orderId: string }): Promise<GetOrderResult> {
    const row = await this.orderRepo.createQueryBuilder('order')
      .leftJoinAndSelect('order.items', 'item')
      .where('order.orderId = :orderId', { orderId: param.orderId })
      .getOne()
    if (!row) throw new Error(ErrorMessage['주문을 찾을 수 없습니다.'])

    return {
      orderId: row.orderId,
      status: row.status,
      totalAmount: row.items.reduce((sum, item) => sum + item.price * item.quantity, 0)
    }
  }
}
