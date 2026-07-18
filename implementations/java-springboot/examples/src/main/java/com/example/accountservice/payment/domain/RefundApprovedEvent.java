package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * 환불 승인으로 발행되는 내부 Domain Event. {@code accountId}/{@code ownerId}는 Refund 자신의 필드가 아니다 — {@link
 * Refund#approve}가 호출될 때 Application 레이어가 이미 로드해둔 원 결제(Payment)에서 얻은 값을 함께 넘겨받아 이 이벤트에 실을 뿐이다.
 * Account BC가 이 이벤트 계열(refund.approved.v1)을 구독해 환불 크레딧(deposit)을 실행한다.
 */
public record RefundApprovedEvent(
        String refundId,
        String paymentId,
        String accountId,
        String ownerId,
        long amount,
        LocalDateTime approvedAt) {}
