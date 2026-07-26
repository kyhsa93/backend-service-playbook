package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RefundEligibilityService is a Domain Service that coordinates rules neither the Payment nor the
 * Refund Aggregate can decide on its own (the original payment's status, the refund amount, and the
 * ML history-pattern fraud-risk score) — since it carries no framework annotations, it is
 * instantiated directly with {@code new} (no Spring context, no ML call — the score is always
 * passed in as a plain value) to verify only the eligibility logic.
 */
class RefundEligibilityServiceTest {

    private final RefundEligibilityService service = new RefundEligibilityService();

    // A safe value below the ML_FRAUD_RISK_REJECTION_THRESHOLD (0.8), used in tests that aren't
    // exercising that specific branch.
    private static final double SAFE_ML_SCORE = 0;

    private Payment completedPayment(long amount) {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", amount);
        payment.complete();
        return payment;
    }

    @Test
    void approves_when_refund_is_at_most_the_payment_amount_on_a_completed_payment() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1000, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund, SAFE_ML_SCORE);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isNull();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void rejects_a_refund_for_a_payment_that_is_not_completed() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000); // PENDING
        Refund refund = Refund.create(payment.getPaymentId(), 500, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund, SAFE_ML_SCORE);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT);
    }

    @Test
    void rejects_when_refund_amount_exceeds_the_payment_amount() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1001, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund, SAFE_ML_SCORE);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT);
    }

    @Test
    void rejects_when_the_ml_fraud_risk_score_is_at_or_above_the_threshold() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 500, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund, 0.8);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_PATTERN_FLAGGED_HIGH_RISK);
        assertThat(decision.reason())
                .isEqualTo(
                        "This refund pattern was flagged as high risk by the fraud-risk model and"
                                + " requires manual review.");
    }

    @Test
    void still_approves_when_the_ml_fraud_risk_score_is_just_below_the_threshold() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 500, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund, 0.79);

        assertThat(decision.approved()).isTrue();
    }
}
