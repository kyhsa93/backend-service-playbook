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
    // raw SQL — TypeORM의 자동 soft-delete 필터를 우회하는데 deletedAt 필터가 없음
    const rows = await this.orderRepo.manager.query(`SELECT * FROM "order" LIMIT ${query.take} OFFSET ${query.page * query.take}`)
    return { orders: rows.map((row: { orderId: string; status: string }) => new Order({ orderId: row.orderId, status: row.status })), count: rows.length }
  }
}
