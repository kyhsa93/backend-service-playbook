import { TransactionType } from '@/account/domain/transaction'

// A plain, narrow shape — only the fields that can safely narrow WHAT is returned. Deliberately
// has no `accountId`/`ownerId` field: the Query Handler that calls this Technical Service always
// scopes the lookup to the authenticated requester's own account, and never lets a value derived
// from the LLM's interpretation of free text influence WHO the data belongs to.
export interface TransactionFilter {
  readonly type?: TransactionType
  readonly fromDate?: string // ISO 8601 date, inclusive
  readonly toDate?: string // ISO 8601 date, inclusive
}

// A Technical Service (see root docs/architecture/domain-service.md) translating a free-text
// question about an account's transaction history into a structured filter. This is the
// "Retrieve"-preparation step of a structured-data RAG pipeline: NlTransactionAnswerComposer
// (the "Generate" step) is the other half, and AccountQuery.getTransactions itself is the
// "Retrieve" step in between.
export abstract class NlTransactionQueryTranslator {
  abstract translate(question: string): Promise<TransactionFilter>
}
