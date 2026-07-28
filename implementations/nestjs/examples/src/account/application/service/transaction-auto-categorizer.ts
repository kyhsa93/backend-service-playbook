import { TransactionCategory } from '@/account/domain/transaction'

// A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
// call — the same placement/shape as NlTransactionQueryTranslator, just classifying a
// merchantName + amount into a fixed category instead of translating a question into a filter.
export abstract class TransactionAutoCategorizer {
  abstract categorize(params: { merchantName: string; amount: number }): Promise<TransactionCategory>
}
