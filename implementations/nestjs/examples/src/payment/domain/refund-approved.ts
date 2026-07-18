// Refund만으로는 accountId/ownerId를 모른다(Refund는 paymentId만 참조).
// approve() 호출 시 Application 레이어가 이미 로드해둔 Payment에서 얻은 값을 함께
// 넘겨받아 이 이벤트에 싣는다 — Refund Aggregate 자신의 필드로 상시 보관하지는 않는다.
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
