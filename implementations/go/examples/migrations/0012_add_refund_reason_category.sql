-- reason_category is filled in asynchronously by ClassifyRefundReasonEventHandler
-- (an Ollama-backed Technical Service, see
-- internal/application/event/refund_reason_classifier.go) reacting to
-- RefundRequested — null until that reaction runs. Nullable.
ALTER TABLE refunds ADD COLUMN reason_category VARCHAR(20) NULL;
