-- Columns supporting scheduling/batch (docs/architecture/scheduling.md) + the
-- Task Outbox table.
--
-- accounts.last_interest_paid_at / cards.last_statement_sent_month are
-- idempotency markers for the daily interest payment batch
-- (Account.ApplyInterest) and the monthly card usage statement batch
-- (Card.MarkStatementSent) respectively (Level 1 — inherent idempotency, the
-- 3 idempotency levels in docs/architecture/domain-events.md) — even if the
-- same Task is re-run at-least-once, these columns alone prevent duplicate
-- payment/duplicate sending. There is no separate processing-record (Ledger)
-- table because both batches can fully determine "has this cycle already
-- been processed" from the Aggregate's own state alone.
ALTER TABLE accounts ADD COLUMN last_interest_paid_at TIMESTAMP NULL;
ALTER TABLE cards ADD COLUMN last_statement_sent_month VARCHAR(7) NULL;

-- task_outbox is a dedicated table for applying the same atomicity guarantee
-- as the outbox table (commit within the same transaction) to Task Queue
-- writes as well — the Scheduler (Cron) has no transaction context, so a
-- single row insert is the entirety of the atomicity here (scheduling.md,
-- "Task Outbox Pattern"). It uses a separate table and separate queue,
-- conceptually distinct from outbox (Domain/Integration Events, "a fact
-- occurred") — this one means "command: perform X." The column structure is
-- the same, but the semantic unit differs, so events and Tasks are not mixed
-- into one table (domain-events.md, "Task Queue vs Domain Event").
--
-- A UNIQUE constraint on dedup_id lets the DB itself guarantee that "a Cron
-- enqueue for the same date/period is never written twice" (the date-based
-- deduplicationId pattern from scheduling.md) — even if multiple instances
-- run the same tick concurrently, INSERT ... ON CONFLICT (dedup_id) DO
-- NOTHING leaves only one row.
CREATE TABLE task_outbox (
  task_id     VARCHAR(36)  PRIMARY KEY,
  task_type   VARCHAR(50)  NOT NULL,
  payload     JSONB        NOT NULL,
  dedup_id    VARCHAR(100) NOT NULL,
  processed   BOOLEAN      NOT NULL DEFAULT false,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_task_outbox_dedup_id UNIQUE (dedup_id)
);

CREATE INDEX idx_task_outbox_unprocessed ON task_outbox (created_at) WHERE processed = false;
