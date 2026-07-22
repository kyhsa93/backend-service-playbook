-- SendMonthlyCardStatementsService (PaymentAdapter.summarizePayments) queries by cardId + period + status,
-- so the existing owner_id index alone doesn't cover this query pattern.
CREATE INDEX idx_payments_card_id_status_created_at ON payments (card_id, status, created_at);
