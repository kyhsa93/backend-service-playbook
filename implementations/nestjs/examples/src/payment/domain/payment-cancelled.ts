export class PaymentCancelled {
  public readonly paymentId: string
  public readonly accountId: string
  public readonly ownerId: string
  public readonly amount: number
  public readonly reason: string
  public readonly cancelledAt: Date

  constructor(params: {
    paymentId: string
    accountId: string
    ownerId: string
    amount: number
    reason: string
    cancelledAt: Date
  }) {
    this.paymentId = params.paymentId
    this.accountId = params.accountId
    this.ownerId = params.ownerId
    this.amount = params.amount
    this.reason = params.reason
    this.cancelledAt = params.cancelledAt
  }
}
