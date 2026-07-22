import { Money } from '@/account/domain/money'

// Similar in shape to MoneyDeposited, but a separate event — "the user made a deposit" and
// "the system paid interest" are different facts, so it's kept separate so the consuming
// side (notification text, etc.) can judge it independently (see
// account/application/event/interest-paid-handler.ts).
export class InterestPaid {
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
