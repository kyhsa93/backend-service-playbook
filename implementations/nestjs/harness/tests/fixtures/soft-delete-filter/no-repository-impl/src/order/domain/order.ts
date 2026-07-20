export class Order {
  public readonly orderId: string

  constructor(params: { orderId: string }) {
    this.orderId = params.orderId
  }
}
