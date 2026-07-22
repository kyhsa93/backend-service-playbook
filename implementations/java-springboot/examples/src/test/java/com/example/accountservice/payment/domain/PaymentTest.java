package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentTest {

    private Payment createPayment() {
        return Payment.create("card-1", "account-1", "owner-1", 1000);
    }

    @Test
    void creating_starts_as_PENDING_with_no_events() {
        Payment payment = createPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getCardId()).isEqualTo("card-1");
        assertThat(payment.getAccountId()).isEqualTo("account-1");
        assertThat(payment.getOwnerId()).isEqualTo("owner-1");
        assertThat(payment.getAmount()).isEqualTo(1000);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    void payment_id_is_a_32_character_hex_string_with_no_hyphens() {
        Payment payment = createPayment();

        assertThat(payment.getPaymentId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void completing_a_PENDING_payment_moves_to_COMPLETED_and_collects_PaymentCompletedEvent() {
        Payment payment = createPayment();

        payment.complete();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(PaymentCompletedEvent.class);
    }

    @Test
    void throws_exception_when_completing_a_non_PENDING_payment() {
        Payment payment = createPayment();
        payment.complete();

        assertThatThrownBy(payment::complete)
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT);
    }

    @Test
    void failing_a_PENDING_payment_moves_to_FAILED() {
        Payment payment = createPayment();

        payment.fail("gateway error");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void throws_exception_when_failing_a_non_PENDING_payment() {
        Payment payment = createPayment();
        payment.complete();

        assertThatThrownBy(() -> payment.fail("gateway error"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT);
    }

    @Test
    void cancelling_a_COMPLETED_payment_moves_to_CANCELLED_and_collects_PaymentCancelledEvent() {
        Payment payment = createPayment();
        payment.complete();
        payment.pullDomainEvents();

        payment.cancel("customer request");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(PaymentCancelledEvent.class);
    }

    @Test
    void throws_exception_when_cancelling_a_non_COMPLETED_payment() {
        Payment payment = createPayment();

        assertThatThrownBy(() -> payment.cancel("customer request"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT);
    }

    @Test
    void throws_exception_when_cancelling_an_already_cancelled_payment() {
        Payment payment = createPayment();
        payment.complete();
        payment.cancel("customer request");

        assertThatThrownBy(() -> payment.cancel("customer request"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT);
    }
}
