// Refund alone doesn't know accountId/ownerId (Refund only references paymentId).
// When approve() is called, values obtained from the Payment the Application layer already
// loaded are passed in together and carried on this event — they're never kept as a
// permanent field on the Refund Aggregate itself.
export class RefundApproved {
  public readonly refundId: string
  public readonly paymentId: string
  public readonly accountId: string
  public readonly ownerId: string
  public readonly amount: number
  public readonly approvedAt: Date

  constructor(params: {
    refundId: string
    paymentId: string
    accountId: string
    ownerId: string
    amount: number
    approvedAt: Date
  }) {
    this.refundId = params.refundId
    this.paymentId = params.paymentId
    this.accountId = params.accountId
    this.ownerId = params.ownerId
    this.amount = params.amount
    this.approvedAt = params.approvedAt
  }
}
