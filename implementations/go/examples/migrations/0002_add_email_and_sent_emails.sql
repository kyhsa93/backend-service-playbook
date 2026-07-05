ALTER TABLE accounts ADD COLUMN email VARCHAR(255) NOT NULL DEFAULT '';

CREATE TABLE sent_emails (
  id             VARCHAR(36)  PRIMARY KEY,
  account_id     VARCHAR(36)  NOT NULL,
  event_type     VARCHAR(30)  NOT NULL,
  recipient      VARCHAR(255) NOT NULL,
  subject        VARCHAR(255) NOT NULL,
  ses_message_id VARCHAR(255) NOT NULL,
  sent_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE INDEX idx_sent_emails_account_id ON sent_emails (account_id);
