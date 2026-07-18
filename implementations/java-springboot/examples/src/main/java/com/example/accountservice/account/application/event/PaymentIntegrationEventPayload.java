package com.example.accountservice.account.application.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payment BC가 발행한 Integration Event({@code payment.completed.v1}/{@code
 * payment.cancelled.v1}/{@code refund.approved.v1}) 페이로드 중 Account BC가 필요로 하는 필드만 담는 로컬 뷰. Payment
 * BC의 Integration Event 클래스를 직접 import하지 않고(공개 계약은 이벤트 타입 문자열 + JSON 스키마다), 3개 이벤트 타입이 공유하는 최소 필드만
 * 읽는다. {@code paymentId}는 payment.completed.v1/payment.cancelled.v1의 상관관계 키, {@code refundId}는
 * refund.approved.v1의 상관관계 키다 — 각 핸들러는 자신이 구독하는 이벤트 타입에 맞는 필드만 사용한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentIntegrationEventPayload(
        String paymentId, String refundId, String accountId, long amount) {}
