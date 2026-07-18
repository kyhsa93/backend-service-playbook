package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentTest {

    private Payment createPayment() {
        return Payment.create("card-1", "account-1", "owner-1", 1000);
    }

    @Test
    void 생성하면_PENDING_상태이고_이벤트가_없다() {
        Payment payment = createPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getCardId()).isEqualTo("card-1");
        assertThat(payment.getAccountId()).isEqualTo("account-1");
        assertThat(payment.getOwnerId()).isEqualTo("owner-1");
        assertThat(payment.getAmount()).isEqualTo(1000);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    void 결제_ID는_하이픈_없는_32자리_hex_문자열이다() {
        Payment payment = createPayment();

        assertThat(payment.getPaymentId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void PENDING_결제를_완료하면_COMPLETED_상태이고_PaymentCompletedEvent가_수집된다() {
        Payment payment = createPayment();

        payment.complete();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(PaymentCompletedEvent.class);
    }

    @Test
    void PENDING이_아닌_결제를_완료하면_예외를_던진다() {
        Payment payment = createPayment();
        payment.complete();

        assertThatThrownBy(payment::complete)
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT);
    }

    @Test
    void PENDING_결제를_실패처리하면_FAILED_상태가_된다() {
        Payment payment = createPayment();

        payment.fail("게이트웨이 오류");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void PENDING이_아닌_결제를_실패처리하면_예외를_던진다() {
        Payment payment = createPayment();
        payment.complete();

        assertThatThrownBy(() -> payment.fail("게이트웨이 오류"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT);
    }

    @Test
    void COMPLETED_결제를_취소하면_CANCELLED_상태이고_PaymentCancelledEvent가_수집된다() {
        Payment payment = createPayment();
        payment.complete();
        payment.pullDomainEvents();

        payment.cancel("고객 요청");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(PaymentCancelledEvent.class);
    }

    @Test
    void COMPLETED가_아닌_결제를_취소하면_예외를_던진다() {
        Payment payment = createPayment();

        assertThatThrownBy(() -> payment.cancel("고객 요청"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT);
    }

    @Test
    void 취소된_결제를_다시_취소하면_예외를_던진다() {
        Payment payment = createPayment();
        payment.complete();
        payment.cancel("고객 요청");

        assertThatThrownBy(() -> payment.cancel("고객 요청"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT);
    }
}
