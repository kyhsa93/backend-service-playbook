-- The read-model table account.analyze-monthly-spending's ETL writes to, one
-- row per (account_id, analysis_month) — the unique index is the idempotency
-- backstop, the same role as
-- idx_transactions_reference_id_type (docs/architecture/scheduling.md).
CREATE TABLE spending_analysis (
  id                          VARCHAR(36)  PRIMARY KEY,
  account_id                  VARCHAR(36)  NOT NULL,
  analysis_month              VARCHAR(7)   NOT NULL,
  total_amount                BIGINT       NOT NULL,
  transaction_count           INTEGER      NOT NULL,
  average_amount              BIGINT       NOT NULL,
  change_from_previous_month  INTEGER      NOT NULL,
  trend                       VARCHAR(20)  NOT NULL,
  created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE UNIQUE INDEX idx_spending_analysis_account_id_analysis_month ON spending_analysis (account_id, analysis_month);
