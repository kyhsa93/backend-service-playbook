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
	CreatedAt     time.Time
}

func newTransaction(accountID string, txType TransactionType, amount Money) Transaction {
	return Transaction{
		TransactionID: common.NewID(),
		AccountID:     accountID,
		Type:          txType,
		Amount:        amount,
		CreatedAt:     time.Now(),
	}
}
