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
    fun evaluate(
        payment: Payment,
        refund: Refund,
    ): RefundDecision {
        if (payment.status != PaymentStatus.COMPLETED) {
            return RefundDecision(approved = false, reason = "A refund can only be requested for a completed payment.")
        }
        if (refund.amount > payment.amount) {
            return RefundDecision(approved = false, reason = "The refund amount cannot exceed the payment amount.")
        }
        return RefundDecision(approved = true)
    }
}

data class RefundDecision(
    val approved: Boolean,
    val reason: String? = null,
)
