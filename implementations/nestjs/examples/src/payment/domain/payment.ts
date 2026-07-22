import { generateId } from '@/common/generate-id'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { PaymentCancelled } from '@/payment/domain/payment-cancelled'
import { PaymentCompleted } from '@/payment/domain/payment-completed'

export type PaymentDomainEvent = PaymentCompleted | PaymentCancelled

// The Payment Aggregate. It only references which card was used (cardId) and which account
// is the debit target (accountId) — no FK crossing the BC boundary — and the actual
// status·balance judgment for the card·account is finished by the Application layer via a
// synchronous CardAdapter/AccountAdapter (ACL) lookup before this Aggregate is even created.
// Payment itself never knows "is the card active" or "is the balance sufficient."
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

  // A pure creation factory called only after the Application layer's synchronous Adapter call
  // has already judged the card's active status·the account's sufficient balance — it only
  // creates as PENDING, with no event.
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

  // Since the current CreatePaymentCommand judges pass/fail via a synchronous Adapter before
  // creation, there's no path today where a Payment Aggregate fails after being created as
  // PENDING. Still, in case a future scenario has failure arrive asynchronously (like a
  // payment gateway callback), the Aggregate itself keeps the state transition (verified by a Domain unit test).
  public fail(_reason: string): void {
    if (this._status !== PaymentStatus.PENDING) {
      throw new Error(PaymentErrorMessage['결제 대기 상태에서만 실패 처리할 수 있습니다.'])
    }
    this._status = PaymentStatus.FAILED
  }

  // A payment cancellation reverses an already-finalized (COMPLETED) payment, so it's only possible from COMPLETED.
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
