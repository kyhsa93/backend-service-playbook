package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundFindQuery

/**
 * The read-only port. The Refund table itself does not have an ownerId (Refund references the
 * original payment only via paymentId) — ownership verification is done by [GetRefundsService] first
 * querying Payment via [PaymentQuery].
 *
 * `findRefunds` reuses `RefundFindQuery`, as defined by RefundRepository (the write model), as-is
 * (the same pattern as AccountQuery/CardQuery/PaymentQuery).
 */
interface RefundQuery {
    fun findRefunds(query: RefundFindQuery): Pair<List<Refund>, Long>
}
