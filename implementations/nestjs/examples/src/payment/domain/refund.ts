import { generateId } from '@/common/generate-id'
import { RefundStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { RefundApproved } from '@/payment/domain/refund-approved'

// The Refund Aggregate. Refund itself can't judge the original payment (Payment)'s
// status·amount — RefundEligibilityService (a Domain Service) loads both the Payment+Refund
// Aggregates together, coordinates them, and calls approve()/reject() with the result (a RefundDecision).
export class Refund {
  public readonly refundId: string
  public readonly paymentId: string
  public readonly amount: number
  public readonly reason: string
  public readonly createdAt: Date
  private _status: RefundStatus
  private _decisionNote?: string
  private readonly _events: RefundApproved[] = []

  constructor(params: {
    refundId?: string
    paymentId: string
    amount: number
    reason: string
    status: RefundStatus
    decisionNote?: string
    createdAt?: Date
  }) {
    this.refundId = params.refundId ?? generateId()
    this.paymentId = params.paymentId
    this.amount = params.amount
    this.reason = params.reason
    this._status = params.status
    this._decisionNote = params.decisionNote
    this.createdAt = params.createdAt ?? new Date()
  }

  get status(): RefundStatus { return this._status }
  get decisionNote(): string | undefined { return this._decisionNote }
  get domainEvents(): RefundApproved[] { return [...this._events] }

  public static create(params: { paymentId: string; amount: number; reason: string }): Refund {
    return new Refund({
      paymentId: params.paymentId,
      amount: params.amount,
      reason: params.reason,
      status: RefundStatus.REQUESTED
    })
  }

  // paymentContext isn't RefundEligibilityService's judgment — it's just reference data the
  // Application layer passes in, after the judgment, to assemble the Integration Event that
  // will propagate to external BCs (it's never promoted into a field of Refund itself).
  public approve(paymentContext: { accountId: string; ownerId: string }): void {
    if (this._status !== RefundStatus.REQUESTED) {
      throw new Error(PaymentErrorMessage['환불 요청 상태에서만 승인할 수 있습니다.'])
    }
    this._status = RefundStatus.APPROVED
    this._decisionNote = '환불이 승인되었습니다.'
    this._events.push(new RefundApproved({
      refundId: this.refundId,
      paymentId: this.paymentId,
      accountId: paymentContext.accountId,
      ownerId: paymentContext.ownerId,
      amount: this.amount,
      approvedAt: new Date()
    }))
  }

  public reject(reason: string): void {
    if (this._status !== RefundStatus.REQUESTED) {
      throw new Error(PaymentErrorMessage['환불 요청 상태에서만 거부할 수 있습니다.'])
    }
    this._status = RefundStatus.REJECTED
    this._decisionNote = reason
  }

  // Today, refund processing ends once Account subscribes to refund.approved.v1 and executes
  // the credit — there's no callback path notifying Payment BC back that the credit succeeded
  // (nothing on the REST surface). This method is kept for Payment domain's complete state
  // model (verified by a Domain unit test), but no Command calls it currently — it's
  // unwired, for the same reason as Payment.fail().
  public complete(): void {
    if (this._status !== RefundStatus.APPROVED) {
      throw new Error(PaymentErrorMessage['승인된 환불만 완료 처리할 수 있습니다.'])
    }
    this._status = RefundStatus.COMPLETED
  }

  public clearEvents(): void { this._events.length = 0 }
}
