import { Payment } from '@/payment/domain/payment'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { Refund } from '@/payment/domain/refund'
import { RefundReasonClassification } from '@/payment/domain/refund-reason-classification'

export interface RefundDecision {
  readonly approved: boolean
  readonly reason?: string
}

// The fraud-risk score is produced upstream by RefundReasonClassifier (a Technical Service
// wrapping an LLM call) — this Domain Service never calls it and doesn't know an LLM produced
// it. It only receives the already-computed classification as one more plain input alongside
// Payment/Refund, and applies its own fixed threshold. The LLM supplies a signal; this method
// still owns the actual approve/reject judgment.
const FRAUD_RISK_REJECTION_THRESHOLD = 0.7

// A second, independent signal — produced upstream by RefundFraudRiskScorer (a Technical
// Service trained on refund/payment history, see refund-fraud-risk-scorer-native-impl.ts /
// refund-fraud-risk-scorer-http-impl.ts). Kept as its own plain number with its own threshold
// rather than merged into RefundReasonClassification, since it's computed from an entirely
// different input (structured history, not the free-text reason) and can fire independently
// of the LLM's category/score.
const ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8

// A Domain Service — a plain class with no framework decorators (it's not registered in the
// NestJS DI container either. The Application layer creates it directly with `new` when needed).
//
// The judgment "the original payment must be COMPLETED, and the refund amount can't exceed
// the payment amount" can't be made by Payment alone or Refund alone. Payment doesn't know
// about refund attempts against itself (a refund exists only as a Refund Aggregate), and
// Refund doesn't know the original payment's amount·status (it only references it via
// paymentId). Making this judgment requires loading both Aggregates and comparing them side
// by side, so this coordination logic can't go on either Aggregate's method (doing so would
// require receiving the entire other Aggregate as a parameter, collapsing the boundary) — it
// belongs here, in a separate Domain Service. (See the root docs/architecture/domain-service.md.)
export class RefundEligibilityService {
  public evaluate(
    payment: Payment,
    refund: Refund,
    classification: RefundReasonClassification,
    mlFraudRiskScore: number
  ): RefundDecision {
    if (payment.status !== PaymentStatus.COMPLETED) {
      return { approved: false, reason: PaymentErrorMessage['A refund can only be requested for a completed payment.'] }
    }
    if (refund.amount > payment.amount) {
      return { approved: false, reason: PaymentErrorMessage['The refund amount cannot exceed the payment amount.'] }
    }
    if (classification.category === 'fraud_suspected' && classification.fraudRiskScore >= FRAUD_RISK_REJECTION_THRESHOLD) {
      return { approved: false, reason: PaymentErrorMessage['This refund reason was flagged as high fraud risk and requires manual review.'] }
    }
    if (mlFraudRiskScore >= ML_FRAUD_RISK_REJECTION_THRESHOLD) {
      return { approved: false, reason: PaymentErrorMessage['This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.'] }
    }
    return { approved: true }
  }
}
