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
    // mlFraudRiskScore is a plain value already computed upstream by RefundFraudRiskScorer (a
    // Technical Service trained on the requester's own structured refund/payment history — refund
    // count, rejection count, amount ratio, minutes since payment — see
    // payment/application/service/RefundFraudRiskScorer.kt and
    // payment/infrastructure/RefundFraudRiskScorerNativeImpl.kt/RefundFraudRiskScorerHttpImpl.kt).
    // This method never calls it and never trains a model itself; it only weighs the score alongside
    // its other checks and still owns the actual judgment.
    fun evaluate(
        payment: Payment,
        refund: Refund,
        mlFraudRiskScore: Double,
    ): RefundDecision {
        if (payment.status != PaymentStatus.COMPLETED) {
            return RefundDecision(approved = false, reason = "A refund can only be requested for a completed payment.")
        }
        if (refund.amount > payment.amount) {
            return RefundDecision(approved = false, reason = "The refund amount cannot exceed the payment amount.")
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
        // The scorer supplies a signal (the fraud-risk score); this fixed threshold is what actually
        // owns the approve/reject judgment — the scorer can never decide the outcome itself.
        private const val ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8
    }
}

data class RefundDecision(
    val approved: Boolean,
    val reason: String? = null,
)
