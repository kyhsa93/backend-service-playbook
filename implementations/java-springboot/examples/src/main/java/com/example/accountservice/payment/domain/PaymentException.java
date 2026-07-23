package com.example.accountservice.payment.domain;

public class PaymentException extends RuntimeException {

    /**
     * Following the "one ErrorCode per domain guard" principle in error-handling.md, this maps a
     * code to every guard condition the Payment/Refund Aggregate can throw. {@code
     * PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT}/{@code
     * PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT}/{@code
     * REFUND_APPROVE_REQUIRES_REQUESTED_REFUND}/{@code
     * REFUND_REJECT_REQUIRES_REQUESTED_REFUND}/{@code REFUND_COMPLETE_REQUIRES_APPROVED_REFUND} are
     * currently defensive codes unreachable from the REST surface — the Application layer only
     * calls these methods after already guaranteeing the correct preceding state (they pair with
     * guards on domain methods like {@code Payment.fail()}/{@code Refund.complete()} that no
     * Command has wired up yet).
     */
    public enum ErrorCode {
        PAYMENT_NOT_FOUND,
        LINKED_CARD_NOT_FOUND,
        PAYMENT_REQUIRES_ACTIVE_CARD,
        LINKED_ACCOUNT_NOT_FOUND,
        PAYMENT_REQUIRES_ACTIVE_ACCOUNT,
        INSUFFICIENT_BALANCE,
        PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT,
        REFUND_REQUIRES_COMPLETED_PAYMENT,
        REFUND_AMOUNT_EXCEEDS_PAYMENT,
        REFUND_REASON_HIGH_FRAUD_RISK,
        REFUND_PATTERN_FLAGGED_HIGH_RISK,
        PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT,
        PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT,
        REFUND_APPROVE_REQUIRES_REQUESTED_REFUND,
        REFUND_REJECT_REQUIRES_REQUESTED_REFUND,
        REFUND_COMPLETE_REQUIRES_APPROVED_REFUND
    }

    private final ErrorCode code;

    public PaymentException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
