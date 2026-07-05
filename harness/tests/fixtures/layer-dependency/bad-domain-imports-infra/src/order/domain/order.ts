import { OrderRepositoryImpl } from '../infrastructure/order-repository-impl'

export class Order {
  constructor(private readonly repo: OrderRepositoryImpl) {}
}
