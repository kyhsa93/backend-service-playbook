export class OrderRepositoryImpl {
  async findOrders(_q: unknown): Promise<{ orders: unknown[]; count: number }> {
    return { orders: [], count: 0 }
  }

  async saveOrder(_order: unknown): Promise<void> {}

  async deleteOrder(_orderId: string): Promise<void> {}
}
