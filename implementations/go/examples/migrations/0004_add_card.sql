CREATE TABLE cards (
  id          VARCHAR(36)  PRIMARY KEY,
  account_id  VARCHAR(36)  NOT NULL,
  owner_id    VARCHAR(36)  NOT NULL,
  brand       VARCHAR(50)  NOT NULL,
  status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cards_account_id ON cards (account_id);
CREATE INDEX idx_cards_owner_id ON cards (owner_id);
