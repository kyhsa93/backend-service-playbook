package com.example.accountservice.payment.domain

/**
 * The Payment BC's (Payment + Refund) global exception hierarchy — follows the same
 * `sealed class` + [PaymentErrorCode] 1:1 mapping convention as account/domain/AccountException.kt
 * (error-handling.md).
 *
 * [LinkedAccountNotFoundException]/[InsufficientBalanceException] share their names with
 * like-named exceptions in card.domain/account.domain, but are distinct types because they live in
 * different packages — Payment never rethrows an exception type from the Account/Card BC it queried
 * synchronously as-is (to avoid contamination, per the ACL principle); it always translates it into
 * its own type before throwing. Where `common/GlobalExceptionHandler.kt` needs to handle both BCs'
 * like-named exceptions together, it distinguishes them with an import alias.
 */
sealed class PaymentException(
    message: String,
    val code: PaymentErrorCode,
) : RuntimeException(message)

class PaymentNotFoundException(
    paymentId: String,
) : PaymentException("payment not found: $paymentId", PaymentErrorCode.PAYMENT_NOT_FOUND)

class LinkedCardNotFoundException : PaymentException("Could not find the card to link.", PaymentErrorCode.LINKED_CARD_NOT_FOUND)

class PaymentRequiresActiveCardException :
    PaymentException("Payment can only be made with an active card.", PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD)

class LinkedAccountNotFoundException : PaymentException("Could not find the linked account.", PaymentErrorCode.LINKED_ACCOUNT_NOT_FOUND)

class PaymentRequiresActiveAccountException :
    PaymentException("Payment can only be made from an active account.", PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT)

class InsufficientBalanceException :
    PaymentException("The account balance is insufficient to make this payment.", PaymentErrorCode.INSUFFICIENT_BALANCE)

class PaymentCancelRequiresCompletedPaymentException :
    PaymentException("Only a completed payment can be cancelled.", PaymentErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT)

class PaymentCompleteRequiresPendingPaymentException :
    PaymentException("Only a payment in the pending state can be completed.", PaymentErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT)

class PaymentFailRequiresPendingPaymentException :
    PaymentException("Only a payment in the pending state can be marked as failed.", PaymentErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT)

class RefundApproveRequiresRequestedRefundException :
    PaymentException("Only a refund in the requested state can be approved.", PaymentErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND)

class RefundRejectRequiresRequestedRefundException :
    PaymentException("Only a refund in the requested state can be rejected.", PaymentErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND)

class RefundCompleteRequiresApprovedRefundException :
    PaymentException("Only an approved refund can be completed.", PaymentErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND)
