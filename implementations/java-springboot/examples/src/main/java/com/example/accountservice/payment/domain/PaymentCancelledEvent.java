package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * 결제취소(이미 COMPLETED된 결제를 되돌림)로 발행되는 내부 Domain Event. Account BC가 이 이벤트 계열(payment.cancelled.v1)을
 * 구독해 보상 크레딧(deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
 */
public record PaymentCancelledEvent(
        String paymentId,
        String accountId,
        String ownerId,
        long amount,
        String reason,
        LocalDateTime cancelledAt) {}
