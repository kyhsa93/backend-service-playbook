package com.example.accountservice.payment.domain

/**
 * The error codes mapped 1:1 to the exceptions of Payment BC (the two Aggregates Payment + Refund).
 *
 * Just like Account/Card, there is one error-code enum per BC — Refund being a separate Aggregate does
 * not mean it gets a separate ErrorCode enum (the same boundary as the nestjs reference's single
 * `PaymentErrorCode`).
 *
 * [PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT]/[PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT]/
 * [REFUND_COMPLETE_REQUIRES_APPROVED_REFUND] are not currently reachable from the REST surface (there
 * is no Command yet that calls `Payment.fail()`/`Refund.complete()`) — they are still defined along
 * with their guards so that the Aggregate's state machine is complete (verified only by Domain unit
 * tests).
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
