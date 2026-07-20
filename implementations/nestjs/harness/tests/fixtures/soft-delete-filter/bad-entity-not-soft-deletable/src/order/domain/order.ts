export class Order {
  public readonly orderId: string
  public readonly status: string

  constructor(params: { orderId: string; status: string }) {
    this.orderId = params.orderId
    this.status = params.status
  }
}
