package com.example.accountservice.payment.domain

import java.time.LocalDateTime

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

    /**
     * A dedicated aggregate query for
     * [com.example.accountservice.payment.application.service.RefundFraudRiskScorer]'s feature
     * assembly (see `RequestRefundService`) — the same reason `PaymentAdapter.summarizePayments`
     * exists: counting an owner's refund history via [findRefunds] and counting matches in the
     * application layer wouldn't scale once history grows past a single page. [Refund] itself
     * carries no `ownerId` (only `paymentId`), so the implementation must join against `Payment` to
     * filter by owner.
     */
    fun summarizeRefundsByOwner(query: RefundSummaryQuery): RefundSummary
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

data class RefundSummaryQuery(
    val ownerId: String,
    val createdAtFrom: LocalDateTime,
    val status: List<RefundStatus>? = null,
)

data class RefundSummary(
    val count: Long,
)
