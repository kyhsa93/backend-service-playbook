-- The Task Outbox table — a Scheduler (Cron) enqueues a Task and TaskOutboxPoller drains it,
-- publishing to the Task Queue (SQS FIFO). Same rationale and structure as the outbox table
-- (dedicated to Domain/Integration Events), but kept as a separate table because it is
-- conceptually different (a command vs. a fact) (see docs/architecture/scheduling.md).
CREATE TABLE task_outbox (
    task_id VARCHAR(32) NOT NULL PRIMARY KEY,
    task_type VARCHAR(255) NOT NULL,
    payload OID NOT NULL,
    group_id VARCHAR(255) NOT NULL,
    deduplication_id VARCHAR(255) NOT NULL,
    processed BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
