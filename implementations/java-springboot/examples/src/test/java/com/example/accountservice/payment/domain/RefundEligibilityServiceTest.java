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
    void 완료된_결제에_결제금액_이하의_환불이면_승인된다() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1000, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isNull();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void 완료되지_않은_결제에_대한_환불은_거부된다() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000); // PENDING
        Refund refund = Refund.create(payment.getPaymentId(), 500, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT);
    }

    @Test
    void 환불_금액이_결제_금액을_초과하면_거부된다() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1001, "change of mind");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT);
    }
}
