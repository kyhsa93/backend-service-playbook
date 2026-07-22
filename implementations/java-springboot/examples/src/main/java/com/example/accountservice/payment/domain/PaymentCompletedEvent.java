package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * The internal Domain Event published by the Payment Aggregate. {@code accountId}+{@code amount}
 * are what the external BC (Account) actually cares about — the path by which Account subscribes to
 * this event is after it has been translated into the payment.completed.v1 Integration Event (see
 * application/event/).
 */
public record PaymentCompletedEvent(
        String paymentId,
        String cardId,
        String accountId,
        String ownerId,
        long amount,
        LocalDateTime completedAt) {}
