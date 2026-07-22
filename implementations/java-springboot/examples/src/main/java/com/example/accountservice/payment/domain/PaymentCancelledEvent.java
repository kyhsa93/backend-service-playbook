package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * The internal Domain Event published when a payment is cancelled (reversing a payment that was
 * already COMPLETED). The Account BC subscribes to this event series (payment.cancelled.v1) and
 * runs a compensating credit (deposit) — a compensating transaction that reverses the amount
 * already deducted.
 */
public record PaymentCancelledEvent(
        String paymentId,
        String accountId,
        String ownerId,
        long amount,
        String reason,
        LocalDateTime cancelledAt) {}
