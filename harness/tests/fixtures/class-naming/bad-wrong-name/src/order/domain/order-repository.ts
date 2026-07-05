// abstract class but wrong name (missing Repository suffix)
export abstract class OrderRepo {
  abstract findOrders(query: unknown): Promise<unknown>
}
