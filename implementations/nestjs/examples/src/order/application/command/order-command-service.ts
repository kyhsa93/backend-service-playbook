import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
import { CreateOrderCommand } from '@/order/application/command/create-order-command'
import { DeleteOrderCommand } from '@/order/application/command/delete-order-command'
import { Order } from '@/order/domain/order'
import { OrderItem } from '@/order/domain/order-item'
import { OrderRepository } from '@/order/domain/order-repository'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@Injectable()
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async createOrder(command: CreateOrderCommand): Promise<void> {
    const order = new Order({
      userId: command.userId,
      items: command.items.map((i) => new OrderItem(i)),
      status: 'pending'
    })
    await this.orderRepository.saveOrder(order)
  }

  public async cancelOrder(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(ErrorMessage['주문을 찾을 수 없습니다.'])

    order.cancel(command.reason)

    await this.transactionManager.run(async () => {
      await this.orderRepository.saveOrder(order)
    })
  }

  public async deleteOrder(command: DeleteOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(ErrorMessage['주문을 찾을 수 없습니다.'])

    await this.orderRepository.deleteOrder(command.orderId)
  }
}
