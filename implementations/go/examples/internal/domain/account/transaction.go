package account

import (
	"time"

	"github.com/example/account-service/internal/common"
)

type TransactionType string

const (
	TransactionTypeDeposit    TransactionType = "DEPOSIT"
	TransactionTypeWithdrawal TransactionType = "WITHDRAWAL"
	// TransactionTypeInterest is the interest-payment transaction created by
	// Account.ApplyInterest — it's distinguished from Deposit/Withdrawal in
	// that it is a system-triggered (Task Queue batch) transaction, not one
	// requested directly by the user (docs/architecture/scheduling.md).
	TransactionTypeInterest TransactionType = "INTEREST"
)

type Transaction struct {
	TransactionID string
	AccountID     string
	Type          TransactionType
	Amount        Money
	// ReferenceID is an optional field populated only for reactions to an
	// external BC's (Payment) Integration Event (withdraw-by-payment/
	// deposit-by-payment). It's absent (empty string) for deposits/
	// withdrawals requested directly by the user — only Payment-reaction
	// commands correlate it with another BC's Aggregate ID (paymentId/
	// refundId), and this value plus Type together serve as the Level 2
	// Ledger key that prevents duplicate processing on at-least-once
	// redelivery (see docs/architecture/domain-events.md).
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
