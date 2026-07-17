interface Order {
  orderId: string
  items: { itemId: string; quantity: number }[]
}

export class OrderRepositoryImpl {
  public async findOrders(query: { take: number; page: number }): Promise<{ orders: Order[]; count: number }> {
    return { orders: [], count: 0 }
  }

  // "items"는 주문 항목을 뜻하는 정당한 도메인 필드다 — 페이지네이션 응답 래퍼가 아니므로
  // generic-response-key 규칙이 flag하면 안 된다.
  public async saveOrder(order: Order): Promise<void> {
    const payload = { orderId: order.orderId, items: order.items.map((i) => ({ ...i })) }
    void payload
  }
}
