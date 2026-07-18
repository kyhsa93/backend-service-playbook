package com.example.accountservice.payment.domain

/**
 * Payment BC(Payment + Refund) 전역 예외 계층 — account/domain/AccountException.kt와 동일한
 * `sealed class` + [PaymentErrorCode] 1:1 매핑 컨벤션을 따른다(error-handling.md).
 *
 * [LinkedAccountNotFoundException]/[InsufficientBalanceException]은 card.domain/account.domain의
 * 동명 예외와 이름이 같지만 패키지가 다르므로 별개 타입이다 — Payment는 자신이 동기 조회한
 * Account/Card BC의 예외 타입을 그대로 던지지 않고(오염 방지, ACL 원칙) 항상 자기 자신의 타입으로
 * 번역해 던진다. `common/GlobalExceptionHandler.kt`에서 두 BC의 동명 예외를 함께 다룰 때는 import
 * alias로 구분한다.
 */
sealed class PaymentException(
    message: String,
    val code: PaymentErrorCode,
) : RuntimeException(message)

class PaymentNotFoundException(
    paymentId: String,
) : PaymentException("payment not found: $paymentId", PaymentErrorCode.PAYMENT_NOT_FOUND)

class LinkedCardNotFoundException : PaymentException("연결할 카드를 찾을 수 없습니다.", PaymentErrorCode.LINKED_CARD_NOT_FOUND)

class PaymentRequiresActiveCardException : PaymentException("활성 상태의 카드로만 결제할 수 있습니다.", PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD)

class LinkedAccountNotFoundException : PaymentException("연결된 계좌를 찾을 수 없습니다.", PaymentErrorCode.LINKED_ACCOUNT_NOT_FOUND)

class PaymentRequiresActiveAccountException :
    PaymentException("활성 상태의 계좌로만 결제할 수 있습니다.", PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT)

class InsufficientBalanceException : PaymentException("계좌 잔액이 부족하여 결제할 수 없습니다.", PaymentErrorCode.INSUFFICIENT_BALANCE)

class PaymentCancelRequiresCompletedPaymentException :
    PaymentException("완료된 결제만 취소할 수 있습니다.", PaymentErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT)

class PaymentCompleteRequiresPendingPaymentException :
    PaymentException("결제 대기 상태에서만 완료 처리할 수 있습니다.", PaymentErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT)

class PaymentFailRequiresPendingPaymentException :
    PaymentException("결제 대기 상태에서만 실패 처리할 수 있습니다.", PaymentErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT)

class RefundApproveRequiresRequestedRefundException :
    PaymentException("환불 요청 상태에서만 승인할 수 있습니다.", PaymentErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND)

class RefundRejectRequiresRequestedRefundException :
    PaymentException("환불 요청 상태에서만 거부할 수 있습니다.", PaymentErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND)

class RefundCompleteRequiresApprovedRefundException :
    PaymentException("승인된 환불만 완료 처리할 수 있습니다.", PaymentErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND)
