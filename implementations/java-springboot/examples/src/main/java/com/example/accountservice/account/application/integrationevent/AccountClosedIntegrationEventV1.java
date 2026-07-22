package com.example.accountservice.account.application.integrationevent;

import java.time.LocalDateTime;

/**
 * The Integration Event (public contract) that the Account BC exposes to external BCs. It is kept
 * separate from the internal Domain Event ({@code AccountClosedEvent}) with an explicit version
 * ({@link #EVENT_TYPE}).
 */
public record AccountClosedIntegrationEventV1(String accountId, LocalDateTime closedAt) {

    public static final String EVENT_TYPE = "account.closed.v1";
}
