export class Refund {
  public readonly refundId: string
  public readonly paymentId: string
  public readonly amount: number

  constructor(params: { refundId: string; paymentId: string; amount: number }) {
    this.refundId = params.refundId
    this.paymentId = params.paymentId
    this.amount = params.amount
  }
}
