interface Order {
  orderId: string
  items: { itemId: string; quantity: number }[]
}

export class OrderRepositoryImpl {
  public async findOrders(query: { take: number; page: number }): Promise<{ orders: Order[]; count: number }> {
    return { orders: [], count: 0 }
  }

  // "items" is a legitimate domain field meaning order items — since it's not a pagination
  // response wrapper, the generic-response-key rule must not flag it.
  public async saveOrder(order: Order): Promise<void> {
    const payload = { orderId: order.orderId, items: order.items.map((i) => ({ ...i })) }
    void payload
  }
}
