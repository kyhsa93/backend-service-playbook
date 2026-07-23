package com.example.accountservice.payment.domain;

/**
 * Domain Service — a pure class with no framework annotations (not registered as a Spring bean; the
 * Application layer creates it directly with {@code new} when needed. See root
 * docs/architecture/domain-service.md).
 *
 * <p>The judgment "the original payment must be in COMPLETED status, and the refund amount cannot
 * exceed the payment amount" cannot be made by {@link Payment} alone, nor by {@link Refund} alone.
 * {@code Payment} does not know about refund attempts against itself (a refund exists only as a
 * {@code Refund} Aggregate), and {@code Refund} does not know the original payment's amount/status
 * (it only references it via {@code paymentId}). Making this judgment requires loading both
 * Aggregates and comparing them together, so this coordination logic cannot be placed as a method
 * on either Aggregate (doing so would require it to accept the entire other Aggregate as a
 * parameter, breaking the boundary) — this is exactly where a Domain Service belongs.
 *
 * <p>{@code classification} is a plain value already computed upstream by {@code
 * RefundReasonClassifier} (a Technical Service wrapping an LLM call — see the Technical Service
 * section of domain-service.md). This method never calls it and doesn't know an LLM produced the
 * value; it only weighs the fraud-risk signal alongside its other checks and still owns the actual
 * judgment.
 *
 * <p>{@code mlFraudRiskScore} is a second, independent signal — a plain value already computed
 * upstream by {@code RefundFraudRiskScorer} (a Technical Service trained on refund/payment
 * *history*, not the free-text reason {@code classification} above scores). It is kept as its own
 * plain number with its own threshold rather than merged into {@code classification}, since it's
 * computed from an entirely different input (structured history, not free text) and can fire
 * independently of the LLM's category/score.
 */
public class RefundEligibilityService {

    // The fraud-risk score is produced upstream by RefundReasonClassifier (a Technical Service
    // wrapping an LLM call) — this Domain Service never calls it and doesn't know an LLM produced
    // it. It only receives the already-computed classification as one more plain input alongside
    // Payment/Refund, and applies its own fixed threshold. The LLM supplies a signal; this method
    // still owns the actual approve/reject judgment.
    private static final double FRAUD_RISK_REJECTION_THRESHOLD = 0.7;

    // A second, independent signal — produced upstream by RefundFraudRiskScorer (a Technical
    // Service trained on refund/payment history, see infrastructure/RefundFraudRiskScorerNativeImpl
    // / RefundFraudRiskScorerHttpImpl). Kept as its own plain number with its own threshold rather
    // than merged into RefundReasonClassification, since it's computed from an entirely different
    // input (structured history, not the free-text reason) and can fire independently of the
    // LLM's category/score.
    private static final double ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8;

    public RefundDecision evaluate(
            Payment payment,
            Refund refund,
            RefundReasonClassification classification,
            double mlFraudRiskScore) {
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT,
                    "A refund can only be requested for a completed payment.");
        }
        if (refund.getAmount() > payment.getAmount()) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT,
                    "The refund amount cannot exceed the payment amount.");
        }
        if (classification.category() == RefundReasonCategory.FRAUD_SUSPECTED
                && classification.fraudRiskScore() >= FRAUD_RISK_REJECTION_THRESHOLD) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_REASON_HIGH_FRAUD_RISK,
                    "This refund reason was flagged as high fraud risk and requires manual"
                            + " review.");
        }
        if (mlFraudRiskScore >= ML_FRAUD_RISK_REJECTION_THRESHOLD) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_PATTERN_FLAGGED_HIGH_RISK,
                    "This refund pattern was flagged as high risk by the fraud-risk model and"
                            + " requires manual review.");
        }
        return RefundDecision.approve();
    }
}
