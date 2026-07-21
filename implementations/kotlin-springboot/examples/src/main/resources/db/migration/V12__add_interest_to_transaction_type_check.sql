ALTER TABLE transactions
    DROP CONSTRAINT transactions_type_check;

ALTER TABLE transactions
    ADD CONSTRAINT transactions_type_check CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'INTEREST'));
