DROP INDEX IF EXISTS idx_transactions_reference_id_type;
ALTER TABLE transactions DROP COLUMN IF EXISTS reference_id;

DROP INDEX IF EXISTS idx_refunds_payment_id;
DROP TABLE IF EXISTS refunds;

DROP INDEX IF EXISTS idx_payments_account_id;
DROP INDEX IF EXISTS idx_payments_card_id;
DROP INDEX IF EXISTS idx_payments_owner_id;
DROP TABLE IF EXISTS payments;
