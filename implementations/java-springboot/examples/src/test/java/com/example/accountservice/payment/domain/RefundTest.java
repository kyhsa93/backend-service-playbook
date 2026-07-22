package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefundTest {

    private Refund createRefund() {
        return Refund.create("payment-1", 500, "change of mind");
    }

    @Test
    void creating_starts_as_REQUESTED_with_no_events() {
        Refund refund = createRefund();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getPaymentId()).isEqualTo("payment-1");
        assertThat(refund.getAmount()).isEqualTo(500);
        assertThat(refund.getReason()).isEqualTo("change of mind");
        assertThat(refund.getDecisionNote()).isNull();
        assertThat(refund.pullDomainEvents()).isEmpty();
    }

    @Test
    void refund_id_is_a_32_character_hex_string_with_no_hyphens() {
        Refund refund = createRefund();

        assertThat(refund.getRefundId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void approving_a_REQUESTED_refund_moves_to_APPROVED_and_collects_RefundApprovedEvent() {
        Refund refund = createRefund();

        refund.approve("account-1", "owner-1");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(refund.getDecisionNote()).isEqualTo("The refund has been approved.");
        assertThat(refund.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(RefundApprovedEvent.class);
    }

    @Test
    void throws_exception_when_approving_a_non_REQUESTED_refund() {
        Refund refund = createRefund();
        refund.approve("account-1", "owner-1");

        assertThatThrownBy(() -> refund.approve("account-1", "owner-1"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND);
    }

    @Test
    void rejecting_a_REQUESTED_refund_moves_to_REJECTED_and_records_the_reason() {
        Refund refund = createRefund();

        refund.reject("The refund amount exceeds the payment amount.");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(refund.getDecisionNote())
                .isEqualTo("The refund amount exceeds the payment amount.");
        assertThat(refund.pullDomainEvents()).isEmpty();
    }

    @Test
    void throws_exception_when_rejecting_a_non_REQUESTED_refund() {
        Refund refund = createRefund();
        refund.reject("reason");

        assertThatThrownBy(() -> refund.reject("a different reason"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND);
    }

    @Test
    void completing_an_APPROVED_refund_moves_to_COMPLETED() {
        Refund refund = createRefund();
        refund.approve("account-1", "owner-1");

        refund.complete();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void throws_exception_when_completing_a_non_APPROVED_refund() {
        Refund refund = createRefund();

        assertThatThrownBy(refund::complete)
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND);
    }
}
