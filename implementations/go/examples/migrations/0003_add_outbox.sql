CREATE TABLE outbox (
  event_id    VARCHAR(36)  PRIMARY KEY,
  event_type  VARCHAR(50)  NOT NULL,
  payload     JSONB        NOT NULL,
  processed   BOOLEAN      NOT NULL DEFAULT false,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_unprocessed ON outbox (created_at) WHERE processed = false;
