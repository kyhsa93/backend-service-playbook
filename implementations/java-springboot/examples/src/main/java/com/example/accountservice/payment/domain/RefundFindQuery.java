package com.example.accountservice.payment.domain;

// Refund has no ownerId (ownership verification of the original payment is done first through
// Payment — see application/query/GetRefundsService).
public record RefundFindQuery(int page, int take, String refundId, String paymentId) {}
