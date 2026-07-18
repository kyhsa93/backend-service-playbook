package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * Payment Aggregate가 발행하는 내부 Domain Event. {@code accountId}+{@code amount}가 외부 BC(Account)의 실제
 * 관심사다 — Account가 이 이벤트를 구독하는 경로는 payment.completed.v1 Integration Event로 변환된
 * 이후다(application/event/ 참고).
 */
public record PaymentCompletedEvent(
        String paymentId,
        String cardId,
        String accountId,
        String ownerId,
        long amount,
        LocalDateTime completedAt) {}
