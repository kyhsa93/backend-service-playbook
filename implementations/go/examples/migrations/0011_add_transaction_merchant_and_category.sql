-- merchant_name is the payee/memo the requester optionally attaches to a
-- withdrawal at request time; category is filled in later, asynchronously,
-- by CategorizeTransactionHandler (an Ollama-backed Technical Service, see
-- internal/application/event/categorize_transaction_handler.go). Both
-- columns are nullable.
ALTER TABLE transactions ADD COLUMN merchant_name VARCHAR(255) NULL;
ALTER TABLE transactions ADD COLUMN category VARCHAR(20) NULL;
