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

// TransactionCategory is the fixed taxonomy TransactionAutoCategorizer (see
// internal/application/event) classifies a withdrawal's MerchantName into.
// Lives here (not in the application layer) for the same reason
// TransactionType does — it's a value the domain read/write model carries.
type TransactionCategory string

const (
	TransactionCategoryFood          TransactionCategory = "FOOD"
	TransactionCategoryTransport     TransactionCategory = "TRANSPORT"
	TransactionCategoryShopping      TransactionCategory = "SHOPPING"
	TransactionCategoryHousing       TransactionCategory = "HOUSING"
	TransactionCategoryMedical       TransactionCategory = "MEDICAL"
	TransactionCategoryEntertainment TransactionCategory = "ENTERTAINMENT"
	TransactionCategoryUtilities     TransactionCategory = "UTILITIES"
	TransactionCategoryOther         TransactionCategory = "OTHER"
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
	// MerchantName is the payee/memo the requester optionally attaches to a
	// withdrawal at request time — the only free-text signal
	// TransactionAutoCategorizer has to classify against. Empty for
	// deposits/interest and for a withdrawal the requester didn't attach one
	// to.
	MerchantName string
	// Category is filled in asynchronously, after the transaction is
	// created — CategorizeTransactionEventHandler reacts to MoneyWithdrawn and
	// categorizes it later, so this is always empty at the moment
	// Account.Withdraw constructs the Transaction, and only set once
	// Categorize has run (either directly, or via a row a categorization run
	// already updated).
	Category  TransactionCategory
	CreatedAt time.Time
}

func newTransaction(accountID string, txType TransactionType, amount Money, referenceID, merchantName string) Transaction {
	return Transaction{
		TransactionID: common.NewID(),
		AccountID:     accountID,
		Type:          txType,
		Amount:        amount,
		ReferenceID:   referenceID,
		MerchantName:  merchantName,
		CreatedAt:     time.Now(),
	}
}

// Categorize is the domain method CategorizeTransactionEventHandler drives
// TransactionRepository's find→modify→save<Noun> cycle through (see
// docs/architecture/repository-pattern.md) — Transaction is otherwise
// immutable, so this returns a new value rather than mutating in place.
func (t Transaction) Categorize(category TransactionCategory) Transaction {
	t.Category = category
	return t
}
