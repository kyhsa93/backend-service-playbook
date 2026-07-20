import { OrderRepository } from '@/order/domain/order-repository'

export class CreateOrderCommandHandler {
  constructor(private readonly orderRepository: OrderRepository) {}
}
