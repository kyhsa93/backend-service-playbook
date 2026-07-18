package com.example.accountservice.payment.domain

/**
 * Payment BC(Payment + Refund 두 Aggregate)의 예외와 1:1 매핑되는 에러 코드.
 *
 * Account/Card와 마찬가지로 BC 하나당 하나의 에러 코드 enum을 둔다 — Refund가 별도 Aggregate라고
 * 해서 별도의 ErrorCode enum을 두지 않는다(nestjs 레퍼런스의 단일 `PaymentErrorCode`와 동일한 경계).
 *
 * [PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT]/[PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT]/
 * [REFUND_COMPLETE_REQUIRES_APPROVED_REFUND]는 현재 REST 표면에서 도달하지 않는다(`Payment.fail()`/
 * `Refund.complete()`를 호출하는 Command가 아직 없다) — 그래도 Aggregate 상태 기계가 완결되도록
 * 가드와 함께 정의해둔다(Domain 단위 테스트로만 검증됨).
 */
enum class PaymentErrorCode {
    PAYMENT_NOT_FOUND,
    LINKED_CARD_NOT_FOUND,
    PAYMENT_REQUIRES_ACTIVE_CARD,
    LINKED_ACCOUNT_NOT_FOUND,
    PAYMENT_REQUIRES_ACTIVE_ACCOUNT,
    INSUFFICIENT_BALANCE,
    PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT,
    PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT,
    PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT,
    REFUND_APPROVE_REQUIRES_REQUESTED_REFUND,
    REFUND_REJECT_REQUIRES_REQUESTED_REFUND,
    REFUND_COMPLETE_REQUIRES_APPROVED_REFUND,
}
