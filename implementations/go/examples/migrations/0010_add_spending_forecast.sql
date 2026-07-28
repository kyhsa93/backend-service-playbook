-- The read-model table account.forecast-spending's batch job writes to, one
-- row per (account_id, forecast_month) — the unique index is the
-- idempotency backstop, the same role as
-- idx_spending_analysis_account_id_analysis_month (docs/architecture/scheduling.md).
CREATE TABLE spending_forecast (
  id                    VARCHAR(36)  PRIMARY KEY,
  account_id            VARCHAR(36)  NOT NULL,
  forecast_month        VARCHAR(7)   NOT NULL,
  predicted_amount      BIGINT       NOT NULL,
  confidence            VARCHAR(10)  NOT NULL,
  history_months_used   INTEGER      NOT NULL,
  created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE UNIQUE INDEX idx_spending_forecast_account_id_forecast_month ON spending_forecast (account_id, forecast_month);
