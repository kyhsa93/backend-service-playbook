package com.example.accountservice.account.application.integrationevent;

import java.time.LocalDateTime;

/**
 * The Integration Event (public contract) that the Account BC exposes to external BCs. It is kept
 * separate from the internal Domain Event ({@code AccountSuspendedEvent}) to keep the name/schema
 * stable, with an explicit version ({@link #EVENT_TYPE}). The {@code EVENT_TYPE} literal becomes
 * the Outbox row's eventType, and the receiving side (Card BC) subscribes only through that same
 * string contract, without importing this class.
 */
public record AccountSuspendedIntegrationEventV1(String accountId, LocalDateTime suspendedAt) {

    public static final String EVENT_TYPE = "account.suspended.v1";
}
