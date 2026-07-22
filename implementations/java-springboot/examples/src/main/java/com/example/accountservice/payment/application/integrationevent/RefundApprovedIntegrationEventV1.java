package com.example.accountservice.payment.application.integrationevent;

/**
 * The Integration Event (public contract) that the Payment BC exposes to external BCs. Carries only
 * the minimal information Account needs to run the refund credit (deposit).
 */
public record RefundApprovedIntegrationEventV1(
        String refundId, String paymentId, String accountId, long amount) {

    public static final String EVENT_TYPE = "refund.approved.v1";
}
