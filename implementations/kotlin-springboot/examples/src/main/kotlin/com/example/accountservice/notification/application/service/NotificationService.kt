package com.example.accountservice.notification.application.service

/**
 * The Technical Service interface for sending emails.
 *
 * Callers in the Application layer, such as Account domain event listeners, depend only on this
 * interface — the implementation details of the actual sending technology (SES, etc.) are handled by
 * the implementation in the infrastructure layer.
 * (See the Technical Service pattern in docs/architecture/domain-service.md)
 *
 * [sourceEventId] is the `eventId` of the Outbox row that triggered this send — the implementation uses
 * it as the key for Level 2 (Ledger) idempotency to prevent duplicate sends (see "Event Handler
 * idempotency" in domain-events.md).
 */
interface NotificationService {
    fun sendEmail(
        accountId: String,
        eventType: String,
        sourceEventId: String,
        recipient: String,
        subject: String,
        body: String,
    )
}
