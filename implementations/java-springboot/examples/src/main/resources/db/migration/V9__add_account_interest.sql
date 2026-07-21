-- 정기 이자 지급(scheduling.md Feature 1) — "오늘 이미 이자를 지급했는지" 판단하는 Level 1(본질적 멱등)
-- 필드다(account/domain/Account.payInterest() 참고).
ALTER TABLE accounts ADD COLUMN last_interest_paid_at DATE;

-- 이자 지급도 다른 잔액 변경과 동일하게 transactions 테이블에 거래를 남긴다(type=INTEREST) — deposit()이
-- Transaction을 만드는 것과 동일한 이유다. CHECK 제약의 허용 값 목록에 INTEREST를 추가한다.
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'INTEREST'));
