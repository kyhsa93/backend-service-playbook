package com.example.accountservice.payment.application.integrationevent;

/**
 * The Integration Event (public contract) that the Payment BC exposes to external BCs. Carries only
 * the minimal information Account needs to run a compensating credit (deposit).
 */
public record PaymentCancelledIntegrationEventV1(String paymentId, String accountId, long amount) {

    public static final String EVENT_TYPE = "payment.cancelled.v1";
}
