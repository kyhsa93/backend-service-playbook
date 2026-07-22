package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RefundEligibilityService is a Domain Service that coordinates rules neither the Payment nor the
 * Refund Aggregate can decide on its own (the original payment's status + comparing the refund
 * amount) — since it carries no framework annotations, it is instantiated directly with {@code new}
 * (no Spring context) to verify only the eligibility logic.
 */
class RefundEligibilityServiceTest {

    private final RefundEligibilityService service = new RefundEligibilityService();

    private Payment completedPayment(long amount) {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", amount);
        payment.complete();
        return payment;
    }

    @Test
    void approves_when_refund_is_at_most_the_payment_amount_on_a_completed_payment() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1000, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isNull();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void rejects_a_refund_for_a_payment_that_is_not_completed() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000); // PENDING
        Refund refund = Refund.create(payment.getPaymentId(), 500, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT);
    }

    @Test
    void rejects_when_refund_amount_exceeds_the_payment_amount() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1001, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT);
    }
}
