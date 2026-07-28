import { Money } from '@/account/domain/money'

export class MoneyWithdrawn {
  public readonly accountId: string
  public readonly email: string
  public readonly transactionId: string
  public readonly amount: Money
  public readonly balanceAfter: Money
  // Carried through so CategorizeTransactionHandler doesn't need a separate lookup to react —
  // the same reasoning as every other field on this event. Absent when the requester didn't
  // attach one; CategorizeTransactionHandler skips categorization entirely in that case.
  public readonly merchantName?: string
  public readonly createdAt: Date

  constructor(params: {
    accountId: string
    email: string
    transactionId: string
    amount: Money
    balanceAfter: Money
    merchantName?: string
    createdAt: Date
  }) {
    this.accountId = params.accountId
    this.email = params.email
    this.transactionId = params.transactionId
    this.amount = params.amount
    this.balanceAfter = params.balanceAfter
    this.merchantName = params.merchantName
    this.createdAt = params.createdAt
  }
}
