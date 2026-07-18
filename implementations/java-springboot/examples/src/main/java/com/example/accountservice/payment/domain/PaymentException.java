package com.example.accountservice.payment.domain;

public class PaymentException extends RuntimeException {

    /**
     * error-handling.md의 "ErrorCode 1개 = 도메인 가드 1개" 원칙에 따라, Payment/Refund Aggregate가 던질 수 있는 모든 가드
     * 조건에 코드를 매핑한다. {@code PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT}/{@code
     * PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT}/{@code
     * REFUND_APPROVE_REQUIRES_REQUESTED_REFUND}/{@code
     * REFUND_REJECT_REQUIRES_REQUESTED_REFUND}/{@code REFUND_COMPLETE_REQUIRES_APPROVED_REFUND}는 현재
     * REST 표면에서는 도달하지 않는 방어적 코드다 — Application 레이어가 이미 올바른 선행 상태를 보장한 뒤에만 해당 메서드를 호출하기 때문이다({@code
     * Payment.fail()}/{@code Refund.complete()}처럼 어떤 Command도 아직 연결하지 않은 도메인 메서드의 가드와 짝을 이룬다).
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
