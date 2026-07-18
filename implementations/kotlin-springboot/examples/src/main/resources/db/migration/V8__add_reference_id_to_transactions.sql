ALTER TABLE transactions
    ADD COLUMN reference_id VARCHAR(255);

CREATE INDEX idx_transactions_reference_id_type ON transactions (reference_id, type);
