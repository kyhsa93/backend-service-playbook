package com.example.accountservice.account.domain;

/**
 * {@link TransferEligibilityService#evaluate}의 판단 결과. 거부됐을 때 {@code code}는 사용자가 직접 {@code
 * withdraw}/{@code deposit}을 호출했을 때와 완전히 동일한 {@link AccountException.ErrorCode}를 데이터로 들고 있다 —
 * Transfer는 Refund와 달리 자신만의 영속 Aggregate가 없어(거부를 저장할 대상이 없음) 거부가 곧바로 예외로 던져져야 하고, 그 예외는 직접 호출과
 * 클라이언트 입장에서 구분할 수 없어야 한다({@code RefundDecision}과 동일한 형태이지만, Refund의 REJECTED는 저장되는 유효한 도메인 결과인 반면
 * Transfer의 거부는 예외로 이어진다는 점이 다르다).
 */
public record TransferDecision(boolean approved, AccountException.ErrorCode code, String reason) {

    public static TransferDecision approve() {
        return new TransferDecision(true, null, null);
    }

    public static TransferDecision rejected(AccountException.ErrorCode code, String reason) {
        return new TransferDecision(false, code, reason);
    }
}
