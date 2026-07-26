package com.example.accountservice.payment.domain

/**
 * The Refund write-model port. List queries must always verify the original payment's (Payment's)
 * ownership first, so [com.example.accountservice.payment.application.query.RefundQuery] handles that
 * exclusively, but the `findRefunds` signature itself (and the [RefundFindQuery] it's based on) is
 * defined here to match the `find<Noun>s` unification rule required by the root
 * `repository-pattern.md`, and `RefundQuery` reuses it (the same pattern as PaymentRepository/
 * PaymentQuery sharing `PaymentFindQuery`).
 */
interface RefundRepository {
    fun findRefunds(query: RefundFindQuery): Pair<List<Refund>, Long>

    fun saveRefund(refund: Refund)
}

/**
 * Refund does not have an ownerId (it references the original payment only via `paymentId`) —
 * ownership verification is done by `GetRefundsService` first querying Payment via `PaymentQuery`.
 */
data class RefundFindQuery(
    val page: Int,
    val take: Int,
    val refundId: String? = null,
    val paymentId: String? = null,
)
