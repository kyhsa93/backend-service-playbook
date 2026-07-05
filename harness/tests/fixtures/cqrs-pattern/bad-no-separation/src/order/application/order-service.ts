// No command/query separation — everything in one flat service
export class OrderService {
  async placeOrder(_cmd: unknown): Promise<void> {}
  async getOrders(_query: unknown): Promise<unknown> { return [] }
}
