import { generateId } from '@/common/generate-id'
import { Money } from '@/account/domain/money'

// INTEREST is a system-initiated credit, distinct from a deposit (DEPOSIT) the user directly
// requested — it's only ever created as the result of the batch Task that
// account/infrastructure/account-interest-scheduler.ts enqueues daily (see
// Account.applyInterest() in account/domain/account.ts).
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'INTEREST'

export class Transaction {
  public readonly transactionId: string
  public readonly accountId: string
  public readonly type: TransactionType
  public readonly amount: Money
  public readonly createdAt: Date
  // An optional field that lets a transaction created in reaction to an external BC's
  // (Payment's) Integration Event be correlated with that other BC's Aggregate ID
  // (paymentId/refundId). It's absent (undefined) for a deposit/withdrawal the user directly
  // requested — it's filled in only by a Payment-reaction command, and on at-least-once
  // re-receipt, this value is used as the Level 2 Ledger key to prevent duplicate processing
  // (see "Event Handler Idempotency" in docs/architecture/domain-events.md).
  public readonly referenceId?: string

  constructor(params: {
    transactionId?: string
    accountId: string
    type: TransactionType
    amount: Money
    referenceId?: string
    createdAt?: Date
  }) {
    this.transactionId = params.transactionId ?? generateId()
    this.accountId = params.accountId
    this.type = params.type
    this.amount = params.amount
    this.referenceId = params.referenceId
    this.createdAt = params.createdAt ?? new Date()
  }
}
