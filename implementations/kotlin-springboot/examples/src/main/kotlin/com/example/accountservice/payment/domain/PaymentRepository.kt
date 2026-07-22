package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * The Payment write-model port — the interface the Command Service depends on.
 *
 * Read-only queries are separated out into
 * [com.example.accountservice.payment.application.query.PaymentQuery] (cqrs-pattern.md). The actual
 * implementation (PaymentRepositoryImpl) implements both interfaces, but each Service is injected only
 * with the interface type it needs.
 *
 * [findPayments] is used not for list queries but only for a single-record lookup (`take = 1`) that
 * needs ownership verification — `CancelPaymentService`/`RequestRefundService` use it to verify the
 * Payment targeted by a command via `paymentId` + `ownerId`. List queries (`GET /payments`) are
 * handled exclusively by [PaymentQuery].
 */
interface PaymentRepository {
    fun findPayments(query: PaymentFindQuery): Pair<List<Payment>, Long>

    fun savePayment(payment: Payment)
}

data class PaymentFindQuery(
    val page: Int,
    val take: Int,
    val paymentId: String? = null,
    val ownerId: String? = null,
    // A filter dedicated to Card BC's PaymentAdapter (aggregating the card usage statement) — narrows
    // by cardId + period + status to aggregate only "completed payments recently made with this
    // card." It has no effect on the existing REST list query (which uses only ownerId) — all fields
    // are nullable, and the filter is not applied unless given a value.
    val cardId: String? = null,
    val status: List<PaymentStatus>? = null,
    val createdFrom: LocalDateTime? = null,
    val createdTo: LocalDateTime? = null,
)
