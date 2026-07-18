ALTER TABLE sent_emails
    ADD COLUMN source_event_id VARCHAR(255) NOT NULL DEFAULT '';

ALTER TABLE sent_emails
    ALTER COLUMN source_event_id DROP DEFAULT;

ALTER TABLE sent_emails
    ADD CONSTRAINT uk_sent_emails_source_event_id UNIQUE (source_event_id);
