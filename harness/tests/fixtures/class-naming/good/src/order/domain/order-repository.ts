export abstract class OrderRepository {
  abstract findOrders(query: unknown): Promise<unknown>
}
