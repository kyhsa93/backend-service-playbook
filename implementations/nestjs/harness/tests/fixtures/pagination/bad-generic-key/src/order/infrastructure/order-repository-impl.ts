export class OrderRepositoryImpl {
  public async findOrders(query: { take: number; page: number }): Promise<{ items: object[]; count: number }> {
    return { items: [], count: 0 }
  }
}
