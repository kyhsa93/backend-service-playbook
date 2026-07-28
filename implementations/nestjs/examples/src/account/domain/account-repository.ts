import { Account } from '@/account/domain/account'
import { AccountStatus } from '@/account/account-enum'
import { TransactionType } from '@/account/domain/transaction'

export abstract class AccountRepository {
  abstract findAccounts(query: {
    readonly take: number
    readonly page: number
    readonly accountId?: string
    readonly ownerId?: string
    readonly status?: AccountStatus[]
  }): Promise<{ accounts: Account[]; count: number }>

  abstract saveAccount(account: Account): Promise<void>

  // An idempotency check ensuring that a Payment BC Integration Event reaction
  // (withdraw-by-payment/deposit-by-payment) never creates the same transaction twice even
  // under at-least-once re-receipt (a Level 2 Ledger — see docs/architecture/domain-events.md).
  // Unlike Card's state-based idempotency (suspending an already-suspended card is harmless),
  // moving money produces a different result each time it's reapplied, so a separate
  // already-processed check is required.
  //
  // The type must also be checked — a completed payment (WITHDRAWAL) and its cancellation's
  // compensating credit (DEPOSIT) are different transactions that share the same paymentId as
  // their referenceId, so checking referenceId alone would wrongly judge the compensating
  // credit as "already processed" and skip it.
  abstract hasTransactionWithReference(referenceId: string, type: TransactionType): Promise<boolean>

  // Aggregates one account's transactions in a date range — the same shape as
  // PaymentRepository.summarizePayments, used by AnalyzeMonthlySpendingCommandHandler to total
  // up a month's (and the prior month's, for comparison) WITHDRAWAL activity without loading
  // every individual Transaction row into memory.
  abstract summarizeTransactions(query: {
    readonly accountId: string
    readonly type: TransactionType[]
    readonly createdAtFrom: Date
    readonly createdAtTo: Date
  }): Promise<{ count: number; totalAmount: number }>

  // The training data for AnomalyDetectionService — the account's own recent WITHDRAWAL amounts
  // (oldest-to-newest order doesn't matter here, unlike SpendingAnalysisRepository.
  // findRecentAnalyses, since the Domain Service only computes a mean/stddev over the set).
  // excludeTransactionId is the withdrawal being judged itself — by the time
  // DetectWithdrawalAnomalyHandler runs (after the Outbox has delivered MoneyWithdrawn), that
  // transaction is already persisted, so it must be excluded or it would skew its own baseline.
  abstract findRecentWithdrawalAmounts(accountId: string, excludeTransactionId: string, limit: number): Promise<number[]>
}
