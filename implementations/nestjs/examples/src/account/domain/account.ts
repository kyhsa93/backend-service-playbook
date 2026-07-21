import { generateId } from '@/common/generate-id'
import { AccountClosed } from '@/account/domain/account-closed'
import { AccountCreated } from '@/account/domain/account-created'
import { AccountReactivated } from '@/account/domain/account-reactivated'
import { AccountStatus } from '@/account/account-enum'
import { AccountSuspended } from '@/account/domain/account-suspended'
import { InterestPaid } from '@/account/domain/interest-paid'
import { Money } from '@/account/domain/money'
import { MoneyDeposited } from '@/account/domain/money-deposited'
import { MoneyWithdrawn } from '@/account/domain/money-withdrawn'
import { Transaction } from '@/account/domain/transaction'
import { AccountErrorMessage } from '@/account/account-error-message'

export type AccountDomainEvent =
  | AccountCreated
  | MoneyDeposited
  | MoneyWithdrawn
  | InterestPaid
  | AccountSuspended
  | AccountReactivated
  | AccountClosed

// 일 이자율(0.01%) — account.apply-daily-interest Task가 모든 ACTIVE 계좌에 매일
// 적용하는 고정 비율이다. 이자 = floor(balance × DAILY_INTEREST_RATE).
export const DAILY_INTEREST_RATE = 0.0001

// UTC 기준 "같은 날짜"인지 비교한다 — lastInterestPaidAt의 하루 단위 멱등성 판단에 쓴다.
// 서버 로컬 타임존에 의존하지 않도록 UTC 필드만 비교한다.
function isSameUtcDate(a: Date, b: Date): boolean {
  return a.getUTCFullYear() === b.getUTCFullYear()
    && a.getUTCMonth() === b.getUTCMonth()
    && a.getUTCDate() === b.getUTCDate()
}

export class Account {
  public readonly accountId: string
  public readonly ownerId: string
  public readonly email: string
  public readonly createdAt: Date
  private _balance: Money
  private _status: AccountStatus
  private _lastInterestPaidAt: Date | null
  private readonly _events: AccountDomainEvent[] = []
  private readonly _transactions: Transaction[] = []

  constructor(params: {
    accountId?: string
    ownerId: string
    email: string
    balance: Money
    status: AccountStatus
    lastInterestPaidAt?: Date | null
    createdAt?: Date
  }) {
    this.accountId = params.accountId ?? generateId()
    this.ownerId = params.ownerId
    this.email = params.email
    this._balance = params.balance
    this._status = params.status
    this._lastInterestPaidAt = params.lastInterestPaidAt ?? null
    this.createdAt = params.createdAt ?? new Date()
  }

  get balance(): Money { return this._balance }
  get status(): AccountStatus { return this._status }
  get lastInterestPaidAt(): Date | null { return this._lastInterestPaidAt }
  get domainEvents(): AccountDomainEvent[] { return [...this._events] }
  get pendingTransactions(): Transaction[] { return [...this._transactions] }

  public static create(params: { ownerId: string; email: string; currency: string }): Account {
    const account = new Account({
      ownerId: params.ownerId,
      email: params.email,
      balance: new Money({ amount: 0, currency: params.currency }),
      status: AccountStatus.ACTIVE
    })
    account._events.push(new AccountCreated({
      accountId: account.accountId,
      ownerId: account.ownerId,
      email: account.email,
      currency: params.currency,
      createdAt: account.createdAt
    }))
    return account
  }

  public deposit(amount: Money, referenceId?: string): Transaction {
    if (this._status !== AccountStatus.ACTIVE) throw new Error(AccountErrorMessage['활성 상태의 계좌만 입금할 수 있습니다.'])
    if (amount.amount <= 0) throw new Error(AccountErrorMessage['금액은 0보다 커야 합니다.'])

    this._balance = this._balance.add(amount)
    const transaction = new Transaction({ accountId: this.accountId, type: 'DEPOSIT', amount, referenceId })
    this._transactions.push(transaction)
    this._events.push(new MoneyDeposited({
      accountId: this.accountId,
      email: this.email,
      transactionId: transaction.transactionId,
      amount,
      balanceAfter: this._balance,
      createdAt: transaction.createdAt
    }))
    return transaction
  }

  public withdraw(amount: Money, referenceId?: string): Transaction {
    if (this._status !== AccountStatus.ACTIVE) throw new Error(AccountErrorMessage['활성 상태의 계좌만 출금할 수 있습니다.'])
    if (amount.amount <= 0) throw new Error(AccountErrorMessage['금액은 0보다 커야 합니다.'])
    if (this._balance.isLessThan(amount)) throw new Error(AccountErrorMessage['잔액이 부족합니다.'])

    this._balance = this._balance.subtract(amount)
    const transaction = new Transaction({ accountId: this.accountId, type: 'WITHDRAWAL', amount, referenceId })
    this._transactions.push(transaction)
    this._events.push(new MoneyWithdrawn({
      accountId: this.accountId,
      email: this.email,
      transactionId: transaction.transactionId,
      amount,
      balanceAfter: this._balance,
      createdAt: transaction.createdAt
    }))
    return transaction
  }

  // 시스템 주도(system-initiated) 이자 지급. 사용자가 요청하는 유스케이스가 아니므로
  // 기존 deposit()의 입력 검증(command 경유)을 거치지 않고 Aggregate 메서드로 직접
  // 모델링한다(account.apply-daily-interest Task Controller가 이 메서드를 호출한다).
  //
  // 멱등성은 Level 1(본질적 멱등) 전략을 쓴다 — lastInterestPaidAt에 "오늘 이미
  // 지급했는지"를 저장해두고, 같은 날 두 번째 호출은 그냥 무시(null 반환)한다. Task
  // 큐가 at-least-once라 같은 날짜의 Task가 재수신되어도 잔액이 중복 증가하지 않는다
  // (docs/architecture/scheduling.md#멱등성 — Level 1 예시와 동일한 패턴).
  public applyInterest(today: Date): Transaction | null {
    if (this._status !== AccountStatus.ACTIVE) return null

    const alreadyPaidToday = this._lastInterestPaidAt !== null && isSameUtcDate(this._lastInterestPaidAt, today)
    // "오늘 처리했다"는 표시는 이자 금액이 0이어도 남긴다 — 같은 날 재수신된 Task가
    // floor() 계산을 다시 하지 않고 바로 스킵되도록(Level 1 멱등성의 핵심 상태).
    this._lastInterestPaidAt = today
    if (alreadyPaidToday) return null

    const interestAmount = Math.floor(this._balance.amount * DAILY_INTEREST_RATE)
    if (interestAmount <= 0) return null

    this._balance = this._balance.add(new Money({ amount: interestAmount, currency: this._balance.currency }))
    const transaction = new Transaction({ accountId: this.accountId, type: 'INTEREST', amount: new Money({ amount: interestAmount, currency: this._balance.currency }) })
    this._transactions.push(transaction)
    this._events.push(new InterestPaid({
      accountId: this.accountId,
      email: this.email,
      transactionId: transaction.transactionId,
      amount: transaction.amount,
      balanceAfter: this._balance,
      createdAt: transaction.createdAt
    }))
    return transaction
  }

  public suspend(): void {
    if (this._status !== AccountStatus.ACTIVE) throw new Error(AccountErrorMessage['활성 상태의 계좌만 정지할 수 있습니다.'])
    this._status = AccountStatus.SUSPENDED
    this._events.push(new AccountSuspended({ accountId: this.accountId, email: this.email, suspendedAt: new Date() }))
  }

  public reactivate(): void {
    if (this._status !== AccountStatus.SUSPENDED) throw new Error(AccountErrorMessage['정지 상태의 계좌만 재개할 수 있습니다.'])
    this._status = AccountStatus.ACTIVE
    this._events.push(new AccountReactivated({ accountId: this.accountId, email: this.email, reactivatedAt: new Date() }))
  }

  public close(): void {
    if (this._status === AccountStatus.CLOSED) throw new Error(AccountErrorMessage['이미 종료된 계좌입니다.'])
    if (!this._balance.isZero()) throw new Error(AccountErrorMessage['잔액이 0이 아닌 계좌는 종료할 수 없습니다.'])
    this._status = AccountStatus.CLOSED
    this._events.push(new AccountClosed({ accountId: this.accountId, email: this.email, closedAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
  public clearTransactions(): void { this._transactions.length = 0 }
}
