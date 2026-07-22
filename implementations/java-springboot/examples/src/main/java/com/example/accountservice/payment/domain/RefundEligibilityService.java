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
 */
public class RefundEligibilityService {

    public RefundDecision evaluate(Payment payment, Refund refund) {
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
        return RefundDecision.approve();
    }
}
