DROP INDEX IF EXISTS idx_task_outbox_unprocessed;
DROP TABLE IF EXISTS task_outbox;

ALTER TABLE cards DROP COLUMN IF EXISTS last_statement_sent_month;
ALTER TABLE accounts DROP COLUMN IF EXISTS last_interest_paid_at;
