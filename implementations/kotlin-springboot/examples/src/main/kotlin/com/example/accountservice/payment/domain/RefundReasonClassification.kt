package com.example.accountservice.payment.domain

/**
 * The categories a refund's free-text reason can be classified into. A plain Domain-layer type, not
 * tied to how it was produced.
 */
enum class RefundReasonCategory {
    DEFECTIVE_PRODUCT,
    NOT_AS_DESCRIBED,
    DUPLICATE_CHARGE,
    CHANGED_MIND,
    FRAUD_SUSPECTED,
    OTHER,
}

/**
 * The shape of a refund reason's classification result — a plain Domain-layer value, not tied to how
 * it was produced. [RefundEligibilityService] reads this as one more input alongside `Payment`/
 * `Refund`; it never imports the Application-layer `RefundReasonClassifier` interface that produces it
 * (that would violate domain-layer isolation — the Domain layer must not depend on any other layer).
 * The Technical Service interface (`payment/application/service/RefundReasonClassifier.kt`) imports
 * this type from here instead.
 */
data class RefundReasonClassification(
    val category: RefundReasonCategory,
    val fraudRiskScore: Double,
)
