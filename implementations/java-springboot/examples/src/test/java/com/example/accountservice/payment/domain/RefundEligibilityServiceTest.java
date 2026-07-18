package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RefundEligibilityService는 Payment/Refund Aggregate 어느 쪽도 혼자서는 판단할 수 없는 규칙(원 결제 상태 + 환불 금액 비교)을
 * 조율하는 Domain Service다 — 프레임워크 애노테이션이 없으므로 Spring 컨텍스트 없이 {@code new}로 직접 인스턴스화해 판단 로직만 검증한다.
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
        Refund refund = Refund.create(payment.getPaymentId(), 1000, "단순 변심");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isNull();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void 완료되지_않은_결제에_대한_환불은_거부된다() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000); // PENDING
        Refund refund = Refund.create(payment.getPaymentId(), 500, "단순 변심");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT);
    }

    @Test
    void 환불_금액이_결제_금액을_초과하면_거부된다() {
        Payment payment = completedPayment(1000);
        Refund refund = Refund.create(payment.getPaymentId(), 1001, "단순 변심");

        RefundDecision decision = service.evaluate(payment, refund);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT);
    }
}
