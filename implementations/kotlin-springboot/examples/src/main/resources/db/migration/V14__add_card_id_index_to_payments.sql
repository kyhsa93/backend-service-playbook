-- SendMonthlyCardStatementsService(PaymentAdapter.summarizePayments)가 cardId + 기간 + status로
-- 조회하므로, 기존 owner_id 인덱스만으로는 이 조회 패턴을 커버하지 못한다.
CREATE INDEX idx_payments_card_id_status_created_at ON payments (card_id, status, created_at);
