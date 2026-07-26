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
 * <p>{@code mlFraudRiskScore} is a plain value already computed upstream by {@code
 * RefundFraudRiskScorer} (a Technical Service trained on the requester's actual refund/payment
 * *history* — refund count, rejection count, amount ratio, minutes since payment; structured facts
 * the requester cannot simply type something different to fake). This method never calls it and
 * doesn't know an ML model produced the value; it only weighs the fraud-risk signal alongside its
 * other checks and still owns the actual judgment.
 */
public class RefundEligibilityService {

    // A signal produced upstream by RefundFraudRiskScorer (a Technical Service trained on
    // refund/payment history, see infrastructure/RefundFraudRiskScorerNativeImpl /
    // RefundFraudRiskScorerHttpImpl) — this Domain Service never calls it and doesn't know an ML
    // model produced it. It only receives the already-computed score as one more plain input
    // alongside Payment/Refund, and applies its own fixed threshold. The model supplies a signal;
    // this method still owns the actual approve/reject judgment.
    private static final double ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8;

    public RefundDecision evaluate(Payment payment, Refund refund, double mlFraudRiskScore) {
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
        if (mlFraudRiskScore >= ML_FRAUD_RISK_REJECTION_THRESHOLD) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_PATTERN_FLAGGED_HIGH_RISK,
                    "This refund pattern was flagged as high risk by the fraud-risk model and"
                            + " requires manual review.");
        }
        return RefundDecision.approve();
    }
}
