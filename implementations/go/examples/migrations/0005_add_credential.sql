CREATE TABLE credentials (
  id             VARCHAR(36)  PRIMARY KEY,
  user_id        VARCHAR(36)  NOT NULL,
  password_hash  VARCHAR(255) NOT NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_credentials_user_id ON credentials (user_id);
