import { generateId } from '@/common/generate-id'
import { RefundStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { RefundApproved } from '@/payment/domain/refund-approved'

// Refund Aggregate. 원 결제(Payment)의 상태·금액에 대한 판단은 Refund 자신이 할 수
// 없다 — RefundEligibilityService(Domain Service)가 Payment+Refund 두 Aggregate를
// 함께 로드해 조율한 결과(RefundDecision)를 받아 approve()/reject()를 호출한다.
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

  // paymentContext는 RefundEligibilityService의 판단이 아니라, 판단 이후 외부 BC에
  // 전파할 Integration Event를 조립하기 위해 Application 레이어가 넘기는 참조 데이터일
  // 뿐이다(Refund 자신의 필드로 승격하지 않는다).
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

  // 현재는 refund.approved.v1을 Account가 구독해 크레딧을 실행하는 것으로 환불 처리가
  // 끝나고, 그 크레딧 성공을 Payment BC로 다시 알려주는 콜백 경로는 없다(REST 표면에
  // 없음). Payment 도메인의 완결된 상태 모델을 위해 메서드는 남겨두되(Domain 단위
  // 테스트로 검증), 현재 어떤 Command도 이를 호출하지 않는다 — Payment.fail()과 같은
  // 이유로 미연결 상태다.
  public complete(): void {
    if (this._status !== RefundStatus.APPROVED) {
      throw new Error(PaymentErrorMessage['승인된 환불만 완료 처리할 수 있습니다.'])
    }
    this._status = RefundStatus.COMPLETED
  }

  public clearEvents(): void { this._events.length = 0 }
}
