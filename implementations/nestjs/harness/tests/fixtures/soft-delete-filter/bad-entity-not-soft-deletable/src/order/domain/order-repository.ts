import { Order } from '@/order/domain/order'

export abstract class OrderRepository {
  abstract findOrders(query: { readonly take: number; readonly page: number }): Promise<{ orders: Order[]; count: number }>
}
