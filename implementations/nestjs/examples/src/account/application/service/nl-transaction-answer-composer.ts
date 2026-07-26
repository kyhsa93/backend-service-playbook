import { TransactionSummaryResult } from '@/account/application/query/account-result'

// A Technical Service (see root docs/architecture/domain-service.md) generating a natural-
// language answer grounded in already-retrieved transaction records — the "Generate" step of a
// structured-data RAG pipeline. It never queries data itself; the Query Handler retrieves the
// records first (scoped to the authenticated requester) and passes them in here as plain data,
// so this service can never widen what's visible beyond what was already fetched.
export abstract class NlTransactionAnswerComposer {
  abstract compose(question: string, transactions: TransactionSummaryResult[]): Promise<string>
}
