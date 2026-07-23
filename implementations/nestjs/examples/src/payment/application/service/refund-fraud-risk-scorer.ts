import { RefundRiskFeatures } from '@/payment/domain/refund-risk-features'

// A Technical Service (see root docs/architecture/domain-service.md) abstracting an ML
// fraud-risk model — trained on refund/payment history rather than the free-text reason
// RefundReasonClassifier handles. RefundEligibilityService never depends on this interface and
// never trains or calls a model itself — it only ever receives the already-computed score as a
// plain number and applies its own fixed threshold. Two implementations exist side by side
// (refund-fraud-risk-scorer-native-impl.ts, refund-fraud-risk-scorer-http-impl.ts); which one
// is wired up is a config choice (see config/fraud-risk.config.ts), never a Domain concern.
export abstract class RefundFraudRiskScorer {
  abstract score(features: RefundRiskFeatures): Promise<number>
}
