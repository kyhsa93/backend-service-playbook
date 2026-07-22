package com.example.accountservice.payment.domain;

/**
 * The judgment result of {@link RefundEligibilityService#evaluate}. When rejected, {@code code}
 * carries the corresponding {@link PaymentException.ErrorCode} as data — it is not thrown like a
 * domain guard (a refund rejection is a valid domain conclusion, not an error), but this applies
 * the "one message = one code" principle from error-handling.md to the judgment result as well, so
 * the rule that produced the rejection can be traced by code.
 */
public record RefundDecision(boolean approved, PaymentException.ErrorCode code, String reason) {

    public static RefundDecision approve() {
        return new RefundDecision(true, null, null);
    }

    public static RefundDecision rejected(PaymentException.ErrorCode code, String reason) {
        return new RefundDecision(false, code, reason);
    }
}
