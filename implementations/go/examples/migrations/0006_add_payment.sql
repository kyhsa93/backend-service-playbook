-- Payment BC 테이블 추가 — Account, Card에 이어 세 번째 Bounded Context
-- (Payment/Refund 두 Aggregate). card_id/account_id는 각각 Card/Account Aggregate를
-- ID로만 참조하는 외부 참조이며, BC 경계를 넘는 FK 제약은 두지 않는다(각 BC가
-- 독립적으로 배포·변경될 수 있어야 하므로) — Card 테이블 추가 때와 동일한 원칙.
CREATE TABLE payments (
  id          VARCHAR(36)  PRIMARY KEY,
  card_id     VARCHAR(36)  NOT NULL,
  account_id  VARCHAR(36)  NOT NULL,
  owner_id    VARCHAR(36)  NOT NULL,
  amount      BIGINT       NOT NULL,
  status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payments_owner_id ON payments (owner_id);
CREATE INDEX idx_payments_card_id ON payments (card_id);
CREATE INDEX idx_payments_account_id ON payments (account_id);

CREATE TABLE refunds (
  id             VARCHAR(36)  PRIMARY KEY,
  payment_id     VARCHAR(36)  NOT NULL,
  amount         BIGINT       NOT NULL,
  reason         VARCHAR(255) NOT NULL,
  status         VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
  decision_note  VARCHAR(255) NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refunds_payment_id ON refunds (payment_id);

-- transactions.reference_id는 Payment BC의 Integration Event(payment.completed.v1 등)에
-- 대한 Account BC의 반응이 at-least-once 재수신에도 같은 거래를 중복 생성하지 않도록
-- 하는 멱등성 판단 키다(Level 2 Ledger, docs/architecture/domain-events.md 참고).
ALTER TABLE transactions ADD COLUMN reference_id VARCHAR(36) NULL;

-- (reference_id, type) 조합에 한해서만 유니크 — 결제완료(WITHDRAWAL)와 그 결제취소 보상
-- 크레딧(DEPOSIT)은 같은 paymentId를 reference_id로 공유하는 서로 다른 거래이므로
-- reference_id 단독 유니크는 보상 크레딧 삽입 자체를 막아버린다. 동시에 도착한 중복
-- Integration Event가 애플리케이션의 체크(HasTransactionWithReference)를 통과해버리는
-- 극히 짧은 race를 DB 제약으로 한 번 더 막는다(Level 2 Ledger의 방어선을 이중화).
CREATE UNIQUE INDEX idx_transactions_reference_id_type ON transactions (reference_id, type) WHERE reference_id IS NOT NULL;
