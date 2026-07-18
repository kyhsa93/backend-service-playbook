package com.example.accountservice.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefundTest {

    private Refund createRefund() {
        return Refund.create("payment-1", 500, "단순 변심");
    }

    @Test
    void 생성하면_REQUESTED_상태이고_이벤트가_없다() {
        Refund refund = createRefund();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getPaymentId()).isEqualTo("payment-1");
        assertThat(refund.getAmount()).isEqualTo(500);
        assertThat(refund.getReason()).isEqualTo("단순 변심");
        assertThat(refund.getDecisionNote()).isNull();
        assertThat(refund.pullDomainEvents()).isEmpty();
    }

    @Test
    void 환불_ID는_하이픈_없는_32자리_hex_문자열이다() {
        Refund refund = createRefund();

        assertThat(refund.getRefundId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void REQUESTED_환불을_승인하면_APPROVED_상태이고_RefundApprovedEvent가_수집된다() {
        Refund refund = createRefund();

        refund.approve("account-1", "owner-1");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(refund.getDecisionNote()).isEqualTo("환불이 승인되었습니다.");
        assertThat(refund.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(RefundApprovedEvent.class);
    }

    @Test
    void REQUESTED가_아닌_환불을_승인하면_예외를_던진다() {
        Refund refund = createRefund();
        refund.approve("account-1", "owner-1");

        assertThatThrownBy(() -> refund.approve("account-1", "owner-1"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND);
    }

    @Test
    void REQUESTED_환불을_거부하면_REJECTED_상태이고_사유가_기록된다() {
        Refund refund = createRefund();

        refund.reject("환불 금액이 결제 금액을 초과합니다.");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(refund.getDecisionNote()).isEqualTo("환불 금액이 결제 금액을 초과합니다.");
        assertThat(refund.pullDomainEvents()).isEmpty();
    }

    @Test
    void REQUESTED가_아닌_환불을_거부하면_예외를_던진다() {
        Refund refund = createRefund();
        refund.reject("사유");

        assertThatThrownBy(() -> refund.reject("다른 사유"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND);
    }

    @Test
    void APPROVED_환불을_완료하면_COMPLETED_상태가_된다() {
        Refund refund = createRefund();
        refund.approve("account-1", "owner-1");

        refund.complete();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void APPROVED가_아닌_환불을_완료하면_예외를_던진다() {
        Refund refund = createRefund();

        assertThatThrownBy(refund::complete)
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND);
    }
}
