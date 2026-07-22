package com.example.accountservice.payment.application.integrationevent;

/**
 * The Integration Event (public contract) that the Payment BC exposes to external BCs. A thin
 * contract carrying only the minimal information (accountId+amount) Account needs for the actual
 * deduction (withdraw) — it does not expose Payment's internal model such as ownerId/cardId. {@code
 * paymentId} is also carried as a correlation key that the Account BC uses for idempotency judgment
 * (Level 2 Ledger: duplicate check on referenceId+type).
 */
public record PaymentCompletedIntegrationEventV1(String paymentId, String accountId, long amount) {

    public static final String EVENT_TYPE = "payment.completed.v1";
}
