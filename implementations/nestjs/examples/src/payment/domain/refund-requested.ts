// Published unconditionally by Refund.create() — before RefundEligibilityService's
// approve/reject judgment even runs. ClassifyRefundReasonHandler reacts to this to build
// ops-analytics insight from every refund's stated reason, independent of whether the refund
// is ultimately approved or rejected (a rejected refund's reason is just as useful a signal for
// the ops dashboard as an approved one's).
export class RefundRequested {
  public readonly refundId: string
  public readonly paymentId: string
  public readonly reason: string
  public readonly createdAt: Date

  constructor(params: {
    refundId: string
    paymentId: string
    reason: string
    createdAt: Date
  }) {
    this.refundId = params.refundId
    this.paymentId = params.paymentId
    this.reason = params.reason
    this.createdAt = params.createdAt
  }
}
