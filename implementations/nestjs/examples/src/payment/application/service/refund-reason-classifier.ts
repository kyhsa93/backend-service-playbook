import { RefundReasonCategory } from '@/payment/domain/refund'

// A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
// call — the same placement/shape as Account BC's TransactionAutoCategorizer, just classifying
// a refund's free-text reason instead of a transaction's merchant name. Ops-analytics input
// only (see refund-reason-insights-query.ts) — this Technical Service is never invoked from,
// or its result ever read by, RequestRefundCommandHandler/RefundEligibilityService.
export abstract class RefundReasonClassifier {
  abstract classify(reason: string): Promise<RefundReasonCategory>
}
