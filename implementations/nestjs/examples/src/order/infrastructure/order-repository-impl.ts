import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { Order } from '@/order/domain/order'
import { OrderItem } from '@/order/domain/order-item'
import { OrderRepository } from '@/order/domain/order-repository'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'

@Injectable()
export class OrderRepositoryImpl extends OrderRepository {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderItemEntity) private readonly orderItemRepo: Repository<OrderItemEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async findOrders(query: {
    readonly take: number
    readonly page: number
    readonly orderId?: string
    readonly userId?: string
    readonly status?: string[]
  }): Promise<{ orders: Order[]; count: number }> {
    const qb = this.orderRepo.createQueryBuilder('order')
      .leftJoinAndSelect('order.items', 'item')
      .orderBy('order.orderId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.orderId) qb.andWhere('order.orderId = :orderId', { orderId: query.orderId })
    if (query.userId) qb.andWhere('order.userId = :userId', { userId: query.userId })
    if (query.status?.length) qb.andWhere('order.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      orders: rows.map((row) => new Order({
        orderId: row.orderId,
        userId: row.userId,
        items: row.items.map((i) => new OrderItem(i)),
        status: row.status as 'pending' | 'paid' | 'cancelled'
      })),
      count
    }
  }

  public async saveOrder(order: Order): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(OrderEntity, {
      orderId: order.orderId,
      userId: order.userId,
      status: order.status,
      items: order.items.map((i) => ({
        itemId: i.itemId,
        name: i.name,
        price: i.price,
        quantity: i.quantity
      }))
    })
    if (order.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(order.domainEvents)
      order.clearEvents()
    }
  }

  public async deleteOrder(orderId: string): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.softDelete(OrderItemEntity, { orderId })
    await manager.softDelete(OrderEntity, { orderId })
  }
}
