package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery

/**
 * The read-only port — the narrow interface the Query Service depends on (same convention as
 * account/card's `*Query`). Separated from the write model
 * ([com.example.accountservice.payment.domain.PaymentRepository]) to enforce at compile time that the
 * Query Service cannot access write methods like savePayment.
 *
 * `findPayments` reuses `PaymentFindQuery`, as defined by PaymentRepository (the write model), as-is
 * (the same pattern as AccountQuery/CardQuery) — the list query (`GET /payments`) uses only
 * `ownerId`, while a single-record lookup uses the same method with `paymentId` + `ownerId` +
 * `take = 1`.
 */
interface PaymentQuery {
    fun findPayments(query: PaymentFindQuery): Pair<List<Payment>, Long>
}
