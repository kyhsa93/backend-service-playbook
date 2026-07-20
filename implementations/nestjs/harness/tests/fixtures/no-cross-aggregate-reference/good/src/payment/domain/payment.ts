export class Payment {
  public readonly paymentId: string
  public readonly amount: number

  constructor(params: { paymentId: string; amount: number }) {
    this.paymentId = params.paymentId
    this.amount = params.amount
  }
}
