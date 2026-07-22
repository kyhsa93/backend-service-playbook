-- Adds the Payment BC tables — the third Bounded Context after Account and
-- Card (the two Aggregates Payment/Refund). card_id/account_id are external
-- references that refer to the Card/Account Aggregates by ID only, and no FK
-- constraint crosses the BC boundary (since each BC must be able to be
-- deployed and changed independently) — the same principle as when the Card
-- table was added.
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

-- transactions.reference_id is the idempotency-check key that keeps the
-- Account BC's reaction to the Payment BC's Integration Events (e.g.
-- payment.completed.v1) from creating a duplicate transaction even under
-- at-least-once redelivery (Level 2 Ledger, see
-- docs/architecture/domain-events.md).
ALTER TABLE transactions ADD COLUMN reference_id VARCHAR(36) NULL;

-- Unique only for the (reference_id, type) combination — payment completion
-- (WITHDRAWAL) and its cancellation's compensating credit (DEPOSIT) are
-- distinct transactions that share the same paymentId as reference_id, so a
-- unique constraint on reference_id alone would block the compensating
-- credit insert itself. This also closes, via a DB constraint, the
-- extremely narrow race where a duplicate Integration Event arriving
-- concurrently slips past the application's check
-- (HasTransactionWithReference) — doubling up the Level 2 Ledger's defense.
CREATE UNIQUE INDEX idx_transactions_reference_id_type ON transactions (reference_id, type) WHERE reference_id IS NOT NULL;
