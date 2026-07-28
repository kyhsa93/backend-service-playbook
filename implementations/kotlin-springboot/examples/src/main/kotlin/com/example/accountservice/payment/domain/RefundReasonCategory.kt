package com.example.accountservice.payment.domain

/**
 * The fixed taxonomy `RefundReasonClassifier` classifies a refund's free-text reason into, for
 * ops-analytics reporting only (see `RefundReasonInsightsQuery`) — it never feeds back into
 * [RefundEligibilityService]'s approve/reject judgment. Lives here (not in the application layer) for
 * the same reason `TransactionCategory` does — it's a value the domain read/write model carries.
 */
enum class RefundReasonCategory {
    DEFECTIVE_PRODUCT,
    WRONG_ITEM,
    NOT_AS_DESCRIBED,
    CHANGED_MIND,
    LATE_DELIVERY,
    DUPLICATE_CHARGE,
    OTHER,
}
