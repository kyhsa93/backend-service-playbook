import { generateId } from '@/common/generate-id'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { PaymentCancelled } from '@/payment/domain/payment-cancelled'
import { PaymentCompleted } from '@/payment/domain/payment-completed'

export type PaymentDomainEvent = PaymentCompleted | PaymentCancelled

// Payment Aggregate. cardId로 어느 카드를 썼는지, accountId로 어느 계좌가 차감 대상인지
// 참조만 하고(BC 경계를 넘는 FK 없음) 카드·계좌의 실제 상태·잔액 판단은 Application
// 레이어가 CardAdapter/AccountAdapter(ACL)로 동기 조회해 이 Aggregate를 생성하기 전에
// 끝낸다 — Payment 자신은 "카드가 활성인지", "잔액이 충분한지"를 알지 못한다.
export class Payment {
  public readonly paymentId: string
  public readonly cardId: string
  public readonly accountId: string
  public readonly ownerId: string
  public readonly amount: number
  public readonly createdAt: Date
  private _status: PaymentStatus
  private readonly _events: PaymentDomainEvent[] = []

  constructor(params: {
    paymentId?: string
    cardId: string
    accountId: string
    ownerId: string
    amount: number
    status: PaymentStatus
    createdAt?: Date
  }) {
    this.paymentId = params.paymentId ?? generateId()
    this.cardId = params.cardId
    this.accountId = params.accountId
    this.ownerId = params.ownerId
    this.amount = params.amount
    this._status = params.status
    this.createdAt = params.createdAt ?? new Date()
  }

  get status(): PaymentStatus { return this._status }
  get domainEvents(): PaymentDomainEvent[] { return [...this._events] }

  // 카드 활성 여부·계좌 잔액 충분 여부는 이미 Application 레이어의 동기 Adapter 호출로
  // 판정이 끝난 뒤 호출되는 순수 생성 팩토리다 — PENDING으로만 만들고 이벤트는 없다.
  public static create(params: { cardId: string; accountId: string; ownerId: string; amount: number }): Payment {
    return new Payment({
      cardId: params.cardId,
      accountId: params.accountId,
      ownerId: params.ownerId,
      amount: params.amount,
      status: PaymentStatus.PENDING
    })
  }

  public complete(): void {
    if (this._status !== PaymentStatus.PENDING) {
      throw new Error(PaymentErrorMessage['결제 대기 상태에서만 완료 처리할 수 있습니다.'])
    }
    this._status = PaymentStatus.COMPLETED
    this._events.push(new PaymentCompleted({
      paymentId: this.paymentId,
      cardId: this.cardId,
      accountId: this.accountId,
      ownerId: this.ownerId,
      amount: this.amount,
      completedAt: new Date()
    }))
  }

  // 현재 CreatePaymentCommand는 통과 여부를 생성 이전에 동기 Adapter로 판정하므로
  // Payment Aggregate가 PENDING으로 만들어진 뒤 실패하는 경로는 없다. 다만 향후
  // 결제 게이트웨이 콜백처럼 비동기로 실패가 도착하는 시나리오를 대비해 상태 전이
  // 자체는 Aggregate가 갖고 있는다(Domain 단위 테스트로 검증).
  public fail(_reason: string): void {
    if (this._status !== PaymentStatus.PENDING) {
      throw new Error(PaymentErrorMessage['결제 대기 상태에서만 실패 처리할 수 있습니다.'])
    }
    this._status = PaymentStatus.FAILED
  }

  // 결제취소는 이미 확정된(COMPLETED) 결제를 되돌리는 것이므로 COMPLETED에서만 가능하다.
  public cancel(reason: string): void {
    if (this._status !== PaymentStatus.COMPLETED) {
      throw new Error(PaymentErrorMessage['완료된 결제만 취소할 수 있습니다.'])
    }
    this._status = PaymentStatus.CANCELLED
    this._events.push(new PaymentCancelled({
      paymentId: this.paymentId,
      accountId: this.accountId,
      ownerId: this.ownerId,
      amount: this.amount,
      reason,
      cancelledAt: new Date()
    }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
