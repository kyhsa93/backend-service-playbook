export abstract class OrderRepository {
  abstract findOrders(): Promise<void>
}
