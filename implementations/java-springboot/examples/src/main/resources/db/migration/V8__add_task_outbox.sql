-- Task Outbox 테이블 — Scheduler(Cron)가 Task를 적재하고 TaskOutboxPoller가 드레인해 Task Queue(SQS
-- FIFO)로 발행한다. outbox 테이블(Domain/Integration Event 전용)과 동일한 이유·구조지만 개념적으로
-- 다른 것(명령 vs 사실)이라 별도 테이블로 분리한다(docs/architecture/scheduling.md).
CREATE TABLE task_outbox (
    task_id VARCHAR(32) NOT NULL PRIMARY KEY,
    task_type VARCHAR(255) NOT NULL,
    payload OID NOT NULL,
    group_id VARCHAR(255) NOT NULL,
    deduplication_id VARCHAR(255) NOT NULL,
    processed BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
