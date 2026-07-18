package com.example.accountservice.payment.application.integrationevent;

/**
 * Payment BC가 외부 BC에 공개하는 Integration Event (공개 계약). Account가 실제 차감(withdraw)에 필요한 최소 정보(accountId
 * +amount)만 싣는 얇은 계약이다 — ownerId/cardId 등 Payment 내부 모델은 노출하지 않는다. {@code paymentId}는 Account BC가
 * 멱등성 판단(Level 2 Ledger: referenceId+type 중복 체크)에 쓰는 상관관계 키로 함께 싣는다.
 */
public record PaymentCompletedIntegrationEventV1(String paymentId, String accountId, long amount) {

    public static final String EVENT_TYPE = "payment.completed.v1";
}
