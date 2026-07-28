import { generateId } from '@/common/generate-id'
import { Money } from '@/account/domain/money'

// INTEREST is a system-initiated credit, distinct from a deposit (DEPOSIT) the user directly
// requested — it's only ever created as the result of the batch Task that
// account/infrastructure/account-interest-scheduler.ts enqueues daily (see
// Account.applyInterest() in account/domain/account.ts).
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'INTEREST'

// The fixed taxonomy TransactionAutoCategorizer classifies a withdrawal's merchantName into.
// Lives here (not in the application layer) for the same reason SpendingAnalysis's
// SpendingTrend does — it's a value the domain read/write model carries.
export type TransactionCategory = 'FOOD' | 'TRANSPORT' | 'SHOPPING' | 'HOUSING' | 'MEDICAL' | 'ENTERTAINMENT' | 'UTILITIES' | 'OTHER'

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
  // The payee/memo the requester optionally attaches to a withdrawal — the only free-text
  // signal TransactionAutoCategorizer has to classify against. Absent for deposits/interest and
  // for a withdrawal the requester didn't attach one to.
  public readonly merchantName?: string
  // Filled in asynchronously, after the transaction is created — CategorizeTransactionHandler
  // reacts to MoneyWithdrawn and categorizes it later, so this is always undefined at the
  // moment Account.withdraw() constructs the Transaction, and only present when this object is
  // reconstructed from a row that a categorization run has already updated.
  public readonly category?: TransactionCategory

  constructor(params: {
    transactionId?: string
    accountId: string
    type: TransactionType
    amount: Money
    referenceId?: string
    merchantName?: string
    category?: TransactionCategory
    createdAt?: Date
  }) {
    this.transactionId = params.transactionId ?? generateId()
    this.accountId = params.accountId
    this.type = params.type
    this.amount = params.amount
    this.referenceId = params.referenceId
    this.merchantName = params.merchantName
    this.category = params.category
    this.createdAt = params.createdAt ?? new Date()
  }

  // The domain method CategorizeTransactionHandler drives TransactionRepository's find→modify→
  // save<Noun> cycle through (see docs/architecture/repository-pattern.md) — Transaction is
  // otherwise immutable, so this returns a new instance rather than mutating in place.
  public categorize(category: TransactionCategory): Transaction {
    return new Transaction({ ...this, category })
  }
}
