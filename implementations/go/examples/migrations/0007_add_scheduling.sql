-- 스케줄링/배치(docs/architecture/scheduling.md) 지원 컬럼 + Task Outbox 테이블.
--
-- accounts.last_interest_paid_at / cards.last_statement_sent_month는 각각 일일 이자
-- 지급 배치(Account.ApplyInterest)와 월간 카드 사용내역 배치(Card.MarkStatementSent)의
-- 멱등성 마커다(Level 1 — 본질적 멱등, docs/architecture/domain-events.md의 멱등성
-- 3단계) — at-least-once로 같은 Task가 재실행돼도 이 컬럼만으로 중복 지급/중복 발송을
-- 막는다. 별도 처리기록(Ledger) 테이블을 두지 않는 이유는 두 배치 모두 "이번 주기에
-- 이미 처리했는가"를 Aggregate 자신의 상태만으로 완전히 판단할 수 있기 때문이다.
ALTER TABLE accounts ADD COLUMN last_interest_paid_at TIMESTAMP NULL;
ALTER TABLE cards ADD COLUMN last_statement_sent_month VARCHAR(7) NULL;

-- task_outbox는 outbox 테이블과 동일한 원자성 보장(같은 트랜잭션 안에서 커밋)을 Task
-- Queue 적재에도 적용하기 위한 전용 테이블이다 — Scheduler(Cron)는 트랜잭션 문맥이
-- 없으므로 단일 row insert 자체가 원자성의 전부가 된다(scheduling.md, "Task Outbox
-- 패턴"). outbox(Domain/Integration Event, "사실이 일어났다")와 개념적으로 구분되는
-- 별도 테이블·별도 큐를 쓴다("명령: X를 수행하라") — 컬럼 구조는 같지만 의미 단위가
-- 다르므로 이벤트와 Task를 한 테이블에 섞지 않는다(domain-events.md, "Task Queue vs
-- Domain Event").
--
-- dedup_id에 UNIQUE 제약을 걸어 "같은 날짜/기간의 Cron enqueue가 중복 적재되지 않는다"를
-- DB가 직접 보장한다(scheduling.md의 날짜 기반 deduplicationId 패턴) — 여러 인스턴스가
-- 동시에 같은 tick을 실행해도 INSERT ... ON CONFLICT (dedup_id) DO NOTHING으로 1건만
-- 남는다.
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
