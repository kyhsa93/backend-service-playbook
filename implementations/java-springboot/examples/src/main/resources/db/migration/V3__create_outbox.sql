CREATE TABLE outbox (
    event_id VARCHAR(32) NOT NULL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload OID NOT NULL,
    processed BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
