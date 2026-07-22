-- Scheduled interest payment (scheduling.md Feature 1) — a Level 1 (intrinsic idempotency) field
-- used to determine "has interest already been paid today" (see account/domain/Account.payInterest()).
ALTER TABLE accounts ADD COLUMN last_interest_paid_at DATE;

-- Interest payment also leaves a transaction in the transactions table (type=INTEREST) just like
-- any other balance change — the same reason deposit() creates a Transaction. Add INTEREST to the
-- CHECK constraint's list of allowed values.
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'INTEREST'));
