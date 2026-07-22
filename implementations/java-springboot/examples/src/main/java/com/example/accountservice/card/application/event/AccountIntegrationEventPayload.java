package com.example.accountservice.card.application.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A local view holding only the minimal fields the Card BC needs from the payload of the
 * Integration Event (account.suspended.v1 / account.closed.v1) published by the Account BC. It does
 * not directly import the Account BC's Integration Event class (the public contract is the
 * event-type string plus the JSON schema) — it only reads the {@code accountId} field it needs. All
 * other fields such as {@code suspendedAt}/{@code closedAt} are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountIntegrationEventPayload(String accountId) {}
