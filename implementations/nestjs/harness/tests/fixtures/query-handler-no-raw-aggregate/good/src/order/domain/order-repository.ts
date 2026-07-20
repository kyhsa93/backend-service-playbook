import { Order } from '@/order/domain/order'

export abstract class OrderRepository {
  abstract findOrders(query: { take: number; page: number }): Promise<{ orders: Order[]; count: number }>

  abstract saveOrder(order: Order): Promise<void>
}
