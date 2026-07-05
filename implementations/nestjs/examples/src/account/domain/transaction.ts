import { generateId } from '@/common/generate-id'
import { Money } from '@/account/domain/money'

export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL'

export class Transaction {
  public readonly transactionId: string
  public readonly accountId: string
  public readonly type: TransactionType
  public readonly amount: Money
  public readonly createdAt: Date

  constructor(params: {
    transactionId?: string
    accountId: string
    type: TransactionType
    amount: Money
    createdAt?: Date
  }) {
    this.transactionId = params.transactionId ?? generateId()
    this.accountId = params.accountId
    this.type = params.type
    this.amount = params.amount
    this.createdAt = params.createdAt ?? new Date()
  }
}
