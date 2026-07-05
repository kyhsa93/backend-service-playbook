import { generateId } from '@/common/generate-id'
import { AccountClosed } from '@/account/domain/account-closed'
import { AccountCreated } from '@/account/domain/account-created'
import { AccountReactivated } from '@/account/domain/account-reactivated'
import { AccountStatus } from '@/account/account-enum'
import { AccountSuspended } from '@/account/domain/account-suspended'
import { Money } from '@/account/domain/money'
import { MoneyDeposited } from '@/account/domain/money-deposited'
import { MoneyWithdrawn } from '@/account/domain/money-withdrawn'
import { Transaction } from '@/account/domain/transaction'
import { AccountErrorMessage } from '@/account/account-error-message'

export type AccountDomainEvent =
  | AccountCreated
  | MoneyDeposited
  | MoneyWithdrawn
  | AccountSuspended
  | AccountReactivated
  | AccountClosed

export class Account {
  public readonly accountId: string
  public readonly ownerId: string
  public readonly email: string
  public readonly createdAt: Date
  private _balance: Money
  private _status: AccountStatus
  private readonly _events: AccountDomainEvent[] = []
  private readonly _transactions: Transaction[] = []

  constructor(params: {
    accountId?: string
    ownerId: string
    email: string
    balance: Money
    status: AccountStatus
    createdAt?: Date
  }) {
    this.accountId = params.accountId ?? generateId()
    this.ownerId = params.ownerId
    this.email = params.email
    this._balance = params.balance
    this._status = params.status
    this.createdAt = params.createdAt ?? new Date()
  }

  get balance(): Money { return this._balance }
  get status(): AccountStatus { return this._status }
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

  public deposit(amount: Money): Transaction {
    if (this._status !== AccountStatus.ACTIVE) throw new Error(AccountErrorMessage['활성 상태의 계좌만 입금할 수 있습니다.'])
    if (amount.amount <= 0) throw new Error(AccountErrorMessage['금액은 0보다 커야 합니다.'])

    this._balance = this._balance.add(amount)
    const transaction = new Transaction({ accountId: this.accountId, type: 'DEPOSIT', amount })
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

  public withdraw(amount: Money): Transaction {
    if (this._status !== AccountStatus.ACTIVE) throw new Error(AccountErrorMessage['활성 상태의 계좌만 출금할 수 있습니다.'])
    if (amount.amount <= 0) throw new Error(AccountErrorMessage['금액은 0보다 커야 합니다.'])
    if (this._balance.isLessThan(amount)) throw new Error(AccountErrorMessage['잔액이 부족합니다.'])

    this._balance = this._balance.subtract(amount)
    const transaction = new Transaction({ accountId: this.accountId, type: 'WITHDRAWAL', amount })
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
