package com.example.accountservice.payment.application.integrationevent;

/**
 * Payment BC가 외부 BC에 공개하는 Integration Event (공개 계약). Account가 보상 크레딧(deposit)을 실행하는 데 필요한 최소 정보만
 * 싣는다.
 */
public record PaymentCancelledIntegrationEventV1(String paymentId, String accountId, long amount) {

    public static final String EVENT_TYPE = "payment.cancelled.v1";
}
