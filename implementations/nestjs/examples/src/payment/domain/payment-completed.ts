export class PaymentCompleted {
  public readonly paymentId: string
  public readonly cardId: string
  public readonly accountId: string
  public readonly ownerId: string
  public readonly amount: number
  public readonly completedAt: Date

  constructor(params: {
    paymentId: string
    cardId: string
    accountId: string
    ownerId: string
    amount: number
    completedAt: Date
  }) {
    this.paymentId = params.paymentId
    this.cardId = params.cardId
    this.accountId = params.accountId
    this.ownerId = params.ownerId
    this.amount = params.amount
    this.completedAt = params.completedAt
  }
}
