-- Both columns nullable: merchant_name is only ever set for a withdrawal the requester chose to
-- attach one to, and category is filled in later, asynchronously, by
-- CategorizeTransactionEventHandler.
ALTER TABLE transactions ADD COLUMN merchant_name VARCHAR(255);
ALTER TABLE transactions ADD COLUMN category VARCHAR(20);
