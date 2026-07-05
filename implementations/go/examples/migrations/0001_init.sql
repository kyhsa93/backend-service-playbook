CREATE TABLE accounts (
  id          VARCHAR(36)  PRIMARY KEY,
  owner_id    VARCHAR(36)  NOT NULL,
  amount      BIGINT       NOT NULL DEFAULT 0,
  currency    VARCHAR(3)   NOT NULL DEFAULT 'KRW',
  status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at  TIMESTAMP    NULL
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);

CREATE TABLE transactions (
  id          VARCHAR(36)  PRIMARY KEY,
  account_id  VARCHAR(36)  NOT NULL,
  type        VARCHAR(20)  NOT NULL,
  amount      BIGINT       NOT NULL,
  currency    VARCHAR(3)   NOT NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE INDEX idx_transactions_account_id ON transactions (account_id);
CREATE INDEX idx_transactions_created_at ON transactions (created_at);
