package account

import (
	"time"

	"github.com/google/uuid"
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
		TransactionID: uuid.NewString(),
		AccountID:     accountID,
		Type:          txType,
		Amount:        amount,
		CreatedAt:     time.Now(),
	}
}
