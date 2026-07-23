-- Carries the W3C traceparent of the span active when the Domain Event was written
-- (observability.md) across the async boundary: OutboxPoller forwards it as an SQS message
-- attribute (the same mechanism already used for event_type), and OutboxConsumer extracts it to
-- continue the same trace while running the OutboxEventHandler. Nullable: a row written with no
-- active span (e.g. tracing disabled) simply carries no traceparent, and the Consumer falls back
-- to starting a fresh root span.
ALTER TABLE outbox ADD COLUMN traceparent VARCHAR(64);
