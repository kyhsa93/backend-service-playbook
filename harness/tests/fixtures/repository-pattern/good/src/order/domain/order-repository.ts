export abstract class OrderRepository {
  abstract findOrders(query: unknown): Promise<{ orders: unknown[]; count: number }>
  abstract saveOrder(order: unknown): Promise<void>
  abstract deleteOrder(orderId: string): Promise<void>
}
