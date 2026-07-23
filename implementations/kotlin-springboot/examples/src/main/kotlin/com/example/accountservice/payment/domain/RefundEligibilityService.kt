package com.example.accountservice.payment.domain

/**
 * Domain Service — a plain class with no framework annotations (it isn't registered in the Spring DI
 * container either. The Application layer instantiates it directly with `RefundEligibilityService()`
 * whenever it's needed).
 *
 * The judgment that "the original payment must be in the COMPLETED state, and the refund amount
 * cannot exceed the payment amount" can't be made by [Payment] alone, nor by [Refund] alone. Payment
 * doesn't know about refund attempts against itself (a refund only exists as a Refund Aggregate), and
 * Refund doesn't know the original payment's amount/status (it only references it by `paymentId`).
 * Making this judgment requires loading both Aggregates and comparing them side by side, so this
 * coordination logic can't be placed as a method on either Aggregate (doing so would require passing
 * the entire other Aggregate as a parameter, breaking the boundary) — it lives in a separate Domain
 * Service instead (see root `docs/architecture/domain-service.md`, and the Kotlin-specific details in
 * `docs/architecture/domain-service.md`).
 */
class RefundEligibilityService {
    // classification is a plain value already computed upstream by RefundReasonClassifier (a
    // Technical Service wrapping an LLM call — see payment/application/service/RefundReasonClassifier.kt
    // and payment/infrastructure/RefundReasonClassifierImpl.kt). This method never calls it and
    // doesn't know an LLM produced the value; it only weighs the fraud-risk signal alongside its
    // other checks and still owns the actual judgment.
    // mlFraudRiskScore is a plain value already computed upstream by RefundFraudRiskScorer (a
    // Technical Service trained on refund/payment history — see
    // payment/application/service/RefundFraudRiskScorer.kt and
    // payment/infrastructure/RefundFraudRiskScorerNativeImpl.kt/RefundFraudRiskScorerHttpImpl.kt).
    // Kept as its own parameter with its own threshold rather than merged into
    // RefundReasonClassification, since it's computed from an entirely different input (structured
    // history, not the free-text reason) and can fire independently of the LLM's category/score.
    fun evaluate(
        payment: Payment,
        refund: Refund,
        classification: RefundReasonClassification,
        mlFraudRiskScore: Double,
    ): RefundDecision {
        if (payment.status != PaymentStatus.COMPLETED) {
            return RefundDecision(approved = false, reason = "A refund can only be requested for a completed payment.")
        }
        if (refund.amount > payment.amount) {
            return RefundDecision(approved = false, reason = "The refund amount cannot exceed the payment amount.")
        }
        if (classification.category == RefundReasonCategory.FRAUD_SUSPECTED &&
            classification.fraudRiskScore >= FRAUD_RISK_REJECTION_THRESHOLD
        ) {
            return RefundDecision(
                approved = false,
                reason = "This refund reason was flagged as high fraud risk and requires manual review.",
            )
        }
        if (mlFraudRiskScore >= ML_FRAUD_RISK_REJECTION_THRESHOLD) {
            return RefundDecision(
                approved = false,
                reason = "This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.",
            )
        }
        return RefundDecision(approved = true)
    }

    companion object {
        // The LLM supplies a signal (the fraud-risk score); this fixed threshold is what actually
        // owns the approve/reject judgment — the classifier can never decide the outcome itself.
        private const val FRAUD_RISK_REJECTION_THRESHOLD = 0.7

        // A second, independent signal — produced upstream by RefundFraudRiskScorer (a Technical
        // Service trained on refund/payment history). Kept as its own threshold rather than merged
        // with FRAUD_RISK_REJECTION_THRESHOLD above, since it's computed from an entirely different
        // input and can fire independently.
        private const val ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8
    }
}

data class RefundDecision(
    val approved: Boolean,
    val reason: String? = null,
)
