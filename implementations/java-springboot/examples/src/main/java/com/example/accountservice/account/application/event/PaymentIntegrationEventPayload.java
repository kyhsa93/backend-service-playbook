package com.example.accountservice.account.application.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A local view holding only the fields the Account BC needs from the Integration Event payloads
 * ({@code payment.completed.v1}/{@code payment.cancelled.v1}/{@code refund.approved.v1}) published
 * by the Payment BC. It does not import the Payment BC's Integration Event classes directly (the
 * public contract is the event-type string plus the JSON schema) — it reads only the minimal fields
 * shared across the three event types. {@code paymentId} is the correlation key for
 * payment.completed.v1/payment.cancelled.v1, and {@code refundId} is the correlation key for
 * refund.approved.v1 — each handler uses only the field that matches the event type it subscribes
 * to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentIntegrationEventPayload(
        String paymentId, String refundId, String accountId, long amount) {}
