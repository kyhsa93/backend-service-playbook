import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { Order } from '@/order/domain/order'
import { OrderRepository } from '@/order/domain/order-repository'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'

@Injectable()
export class OrderRepositoryImpl extends OrderRepository {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>
  ) {
    super()
  }

  public async findOrders(query: { readonly take: number; readonly page: number }): Promise<{ orders: Order[]; count: number }> {
    const qb = this.orderRepo.createQueryBuilder('order')
      .take(query.take)
      .skip(query.page * query.take)

    const [rows, count] = await qb.getManyAndCount()
    return { orders: rows.map((row) => new Order({ orderId: row.orderId, status: row.status })), count }
  }
}
