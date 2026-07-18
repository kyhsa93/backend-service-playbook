package com.example.accountservice.payment.domain;

/**
 * {@link RefundEligibilityService#evaluate}의 판단 결과. 거부됐을 때 {@code code}는 대응하는 {@link
 * PaymentException.ErrorCode}를 데이터로 들고 있다 — 도메인 가드처럼 throw되지는 않지만(환불 거부는 유효한 도메인 결론이지 에러가 아니다),
 * error-handling.md의 "메시지 1개 = 코드 1개" 원칙을 판단 결과에도 그대로 적용해 어떤 규칙이 거부를 만들었는지 코드로 추적할 수 있게 한다.
 */
public record RefundDecision(boolean approved, PaymentException.ErrorCode code, String reason) {

    public static RefundDecision approve() {
        return new RefundDecision(true, null, null);
    }

    public static RefundDecision rejected(PaymentException.ErrorCode code, String reason) {
        return new RefundDecision(false, code, reason);
    }
}
