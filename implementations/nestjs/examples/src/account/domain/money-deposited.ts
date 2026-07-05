import { Money } from '@/account/domain/money'

export class MoneyDeposited {
  public readonly accountId: string
  public readonly email: string
  public readonly transactionId: string
  public readonly amount: Money
  public readonly balanceAfter: Money
  public readonly createdAt: Date

  constructor(params: {
    accountId: string
    email: string
    transactionId: string
    amount: Money
    balanceAfter: Money
    createdAt: Date
  }) {
    this.accountId = params.accountId
    this.email = params.email
    this.transactionId = params.transactionId
    this.amount = params.amount
    this.balanceAfter = params.balanceAfter
    this.createdAt = params.createdAt
  }
}
