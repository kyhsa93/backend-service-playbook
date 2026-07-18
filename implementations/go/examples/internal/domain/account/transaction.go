package account

import (
	"time"

	"github.com/example/account-service/internal/common"
)

type TransactionType string

const (
	TransactionTypeDeposit    TransactionType = "DEPOSIT"
	TransactionTypeWithdrawal TransactionType = "WITHDRAWAL"
)

type Transaction struct {
	TransactionID string
	AccountID     string
	Type          TransactionType
	Amount        Money
	// ReferenceID는 외부 BC(Payment)의 Integration Event 반응(withdraw-by-payment/
	// deposit-by-payment)에서만 채워지는 선택 필드다. 사용자가 직접 요청한 입금/출금에는
	// 없다(빈 문자열) — Payment 반응 커맨드에서만 다른 BC의 Aggregate ID(paymentId/
	// refundId)로 상관관계를 지어, at-least-once 재수신 시 이 값+Type 조합으로 중복
	// 처리를 막는 Level 2 Ledger 키로 쓰인다(docs/architecture/domain-events.md 참고).
	ReferenceID string
	CreatedAt   time.Time
}

func newTransaction(accountID string, txType TransactionType, amount Money, referenceID string) Transaction {
	return Transaction{
		TransactionID: common.NewID(),
		AccountID:     accountID,
		Type:          txType,
		Amount:        amount,
		ReferenceID:   referenceID,
		CreatedAt:     time.Now(),
	}
}
