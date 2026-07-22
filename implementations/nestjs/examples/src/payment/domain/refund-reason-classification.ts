// The shape of a refund reason's classification result — a plain Domain-layer value, not tied
// to how it was produced. RefundEligibilityService (domain/refund-eligibility-service.ts) reads
// this as one more input alongside Payment/Refund; it never imports the Application-layer
// RefundReasonClassifier interface that produces it (that would violate domain-layer isolation —
// the Domain layer must not depend on any other layer). The Technical Service interface
// (application/service/refund-reason-classifier.ts) imports this type from here instead.
export type RefundReasonCategory =
  | 'defective_product'
  | 'not_as_described'
  | 'duplicate_charge'
  | 'changed_mind'
  | 'fraud_suspected'
  | 'other'

export interface RefundReasonClassification {
  readonly category: RefundReasonCategory
  readonly fraudRiskScore: number
}
