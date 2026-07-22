package com.example.accountservice.payment.domain;

/**
 * The set of categories a refund's free-text reason can be classified into by {@code
 * RefundReasonClassifier} (a Technical Service wrapping an LLM call — see
 * application/service/RefundReasonClassifier.java). A plain Domain-layer enum, not tied to how the
 * classification was produced: {@link RefundEligibilityService} reads it as one more input
 * alongside {@link Payment}/{@link Refund}, and never imports the Application-layer interface that
 * produces it (that would violate domain-layer isolation — the Domain layer must not depend on any
 * other layer). The Technical Service interface imports this type from here instead.
 */
public enum RefundReasonCategory {
    DEFECTIVE_PRODUCT,
    NOT_AS_DESCRIBED,
    DUPLICATE_CHARGE,
    CHANGED_MIND,
    FRAUD_SUSPECTED,
    OTHER
}
