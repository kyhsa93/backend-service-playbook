DROP INDEX IF EXISTS idx_sent_emails_account_id;
DROP TABLE IF EXISTS sent_emails;

ALTER TABLE accounts DROP COLUMN email;
