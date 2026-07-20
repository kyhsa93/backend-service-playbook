import { OrderRepository } from '@/order/domain/order-repository'
import { UserRepository } from '@/user/domain/user-repository'

export class CreateOrderCommandHandler {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly userRepository: UserRepository
  ) {}
}
