import { Transaction } from '@/account/domain/transaction'

// Separate from AccountRepository — that one only ever inserts Transaction rows in bulk as a
// side effect of saveAccount (Transaction rows are otherwise insert-only there). This is the
// find→modify-via-domain-method→save<Noun> cycle CategorizeTransactionHandler needs for the one
// field (category) that legitimately gets set after the fact (see repository-pattern.md's
// "a Repository must not have an update method" rule).
export abstract class TransactionRepository {
  abstract findTransaction(transactionId: string): Promise<Transaction | null>
  abstract saveTransaction(transaction: Transaction): Promise<void>
}
