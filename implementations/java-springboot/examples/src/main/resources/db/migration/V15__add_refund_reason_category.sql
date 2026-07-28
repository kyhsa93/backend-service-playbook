-- Nullable: filled in asynchronously by ClassifyRefundReasonEventHandler, some time after the
-- refund row itself is created.
ALTER TABLE refund ADD COLUMN reason_category VARCHAR(30);
