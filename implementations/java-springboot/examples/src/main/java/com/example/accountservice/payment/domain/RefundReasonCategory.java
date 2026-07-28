package com.example.accountservice.payment.domain;

/**
 * The fixed taxonomy {@code RefundReasonClassifier} classifies a refund's free-text reason into,
 * for ops-analytics reporting only (see {@code RefundReasonInsightsQuery}) — it never feeds back
 * into {@link RefundEligibilityService}'s approve/reject judgment. Lives here (not in the
 * application layer) for the same reason {@code TransactionCategory} does — it's a value the domain
 * read/write model carries.
 */
public enum RefundReasonCategory {
    DEFECTIVE_PRODUCT,
    WRONG_ITEM,
    NOT_AS_DESCRIBED,
    CHANGED_MIND,
    LATE_DELIVERY,
    DUPLICATE_CHARGE,
    OTHER
}
