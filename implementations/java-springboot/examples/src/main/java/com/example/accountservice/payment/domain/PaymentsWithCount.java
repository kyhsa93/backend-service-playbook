package com.example.accountservice.payment.domain;

import java.util.List;

/**
 * The result of a {@code findPayments} query — returns the list together with the total count.
 * Single-record lookups also reuse this type: call it with {@code PaymentFindQuery.take} set to 1,
 * then take the first result from {@code payments()} (see repository-pattern.md).
 */
public record PaymentsWithCount(List<Payment> payments, long count) {}
