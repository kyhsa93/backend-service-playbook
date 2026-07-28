-- The Level 2 (Ledger) idempotency key was originally source_event_id alone, which assumed one
-- Outbox delivery (one eventId) results in at most one sent email. That assumption broke with
-- DetectWithdrawalAnomalyEventHandler: MoneyWithdrawnEvent now has two subscribers
-- (MoneyWithdrawnEventHandler for eventType=MoneyWithdrawn, DetectWithdrawalAnomalyEventHandler
-- for eventType=WithdrawalAnomalyDetected) that both call NotificationService.sendEmail with the
-- SAME sourceEventId (the shared Outbox row's eventId) — the old unique constraint silently
-- treated the second handler's legitimate, distinct email as a duplicate of the first and skipped
-- it. The dedup key must be scoped per (sourceEventId, eventType) — a retried delivery of the same
-- handler still collides (correctly deduped), but two different handlers reacting to one delivery
-- no longer do.
ALTER TABLE sent_emails
    DROP CONSTRAINT uk_sent_emails_source_event_id;

ALTER TABLE sent_emails
    ADD CONSTRAINT uk_sent_emails_source_event_id_event_type UNIQUE (source_event_id, event_type);
