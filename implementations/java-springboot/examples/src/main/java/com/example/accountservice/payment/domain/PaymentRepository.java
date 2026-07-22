package com.example.accountservice.payment.domain;

/**
 * The write-side Repository contract for the Payment Aggregate (owned by domain). Read-only queries
 * are separated into a distinct application/query/PaymentQuery interface (see cqrs-pattern.md) —
 * however, the Command use cases (payment cancellation/refund request) also need a single-record
 * lookup that doubles as ownership verification, so {@code findPayments} exists with the same
 * signature in both interfaces (the same pattern as account/domain/AccountRepository).
 */
public interface PaymentRepository {
    PaymentsWithCount findPayments(PaymentFindQuery query);

    void savePayment(Payment payment);
}
