package account

import (
	"time"

	"github.com/example/account-service/internal/common"
)

type Account struct {
	AccountID string
	OwnerID   string
	Email     string
	Balance   Money
	Status    Status
	CreatedAt time.Time
	UpdatedAt time.Time
	// LastInterestPaidAt is the date ApplyInterest last actually paid interest
	// (reflected it in the balance). A zero time.Time means interest has never
	// been paid. This single field is enough to determine "has interest already
	// been paid today" (Level 1 — inherent idempotency), so if the same day's
	// batch Task is re-run at-least-once, it becomes a natural no-op without a
	// separate Ledger (see the 3 levels of idempotency in
	// docs/architecture/domain-events.md).
	LastInterestPaidAt time.Time
	events             []DomainEvent
	transactions       []Transaction
}

func New(ownerID, email, currency string) *Account {
	now := time.Now()
	a := &Account{
		AccountID: common.NewID(),
		OwnerID:   ownerID,
		Email:     email,
		Balance:   Money{Amount: 0, Currency: currency},
		Status:    StatusActive,
		CreatedAt: now,
		UpdatedAt: now,
	}
	a.events = append(a.events, AccountCreated{
		AccountID: a.AccountID,
		OwnerID:   a.OwnerID,
		Email:     a.Email,
		Currency:  currency,
		CreatedAt: now,
	})
	return a
}

func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt, lastInterestPaidAt time.Time) *Account {
	return &Account{
		AccountID:          accountID,
		OwnerID:            ownerID,
		Email:              email,
		Balance:            balance,
		Status:             status,
		CreatedAt:          createdAt,
		UpdatedAt:          updatedAt,
		LastInterestPaidAt: lastInterestPaidAt,
	}
}

// Deposit processes a deposit. referenceID is an empty string if the deposit
// was requested directly by the user, or the other BC's Aggregate ID
// (paymentId/refundId) if it's a reaction to an external BC's (Payment)
// Integration Event (compensating credit/refund credit) — the caller
// (Application Handler) is responsible for first checking idempotency via
// the Repository's HasTransactionWithReference before calling this method.
func (a *Account) Deposit(amount int64, referenceID string) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrDepositRequiresActiveAccount
	}
	if amount <= 0 {
		return Transaction{}, ErrInvalidAmount
	}
	money, err := NewMoney(amount, a.Balance.Currency)
	if err != nil {
		return Transaction{}, err
	}
	newBalance, err := a.Balance.Add(money)
	if err != nil {
		return Transaction{}, err
	}
	a.Balance = newBalance
	tx := newTransaction(a.AccountID, TransactionTypeDeposit, money, referenceID)
	a.transactions = append(a.transactions, tx)
	a.events = append(a.events, MoneyDeposited{
		AccountID:     a.AccountID,
		Email:         a.Email,
		TransactionID: tx.TransactionID,
		Amount:        money,
		BalanceAfter:  a.Balance,
		CreatedAt:     tx.CreatedAt,
	})
	return tx, nil
}

// Withdraw processes a withdrawal. referenceID follows the same rule as
// Deposit — an empty string if the withdrawal was requested directly by the
// user, or paymentId if it's a reaction to the Payment BC's
// payment.completed.v1 (the actual deduction).
func (a *Account) Withdraw(amount int64, referenceID string) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrWithdrawRequiresActiveAccount
	}
	if amount <= 0 {
		return Transaction{}, ErrInvalidAmount
	}
	money, err := NewMoney(amount, a.Balance.Currency)
	if err != nil {
		return Transaction{}, err
	}
	if a.Balance.LessThan(money) {
		return Transaction{}, ErrInsufficientBalance
	}
	newBalance, err := a.Balance.Subtract(money)
	if err != nil {
		return Transaction{}, err
	}
	a.Balance = newBalance
	tx := newTransaction(a.AccountID, TransactionTypeWithdrawal, money, referenceID)
	a.transactions = append(a.transactions, tx)
	a.events = append(a.events, MoneyWithdrawn{
		AccountID:     a.AccountID,
		Email:         a.Email,
		TransactionID: tx.TransactionID,
		Amount:        money,
		BalanceAfter:  a.Balance,
		CreatedAt:     tx.CreatedAt,
	})
	return tx, nil
}

func (a *Account) Suspend() error {
	if a.Status != StatusActive {
		return ErrSuspendRequiresActiveAccount
	}
	a.Status = StatusSuspended
	a.events = append(a.events, AccountSuspended{AccountID: a.AccountID, Email: a.Email, SuspendedAt: time.Now()})
	return nil
}

func (a *Account) Reactivate() error {
	if a.Status != StatusSuspended {
		return ErrReactivateRequiresSuspendedAccount
	}
	a.Status = StatusActive
	a.events = append(a.events, AccountReactivated{AccountID: a.AccountID, Email: a.Email, ReactivatedAt: time.Now()})
	return nil
}

func (a *Account) Close() error {
	if a.Status == StatusClosed {
		return ErrAlreadyClosed
	}
	if !a.Balance.IsZero() {
		return ErrBalanceNotZero
	}
	a.Status = StatusClosed
	a.events = append(a.events, AccountClosed{AccountID: a.AccountID, Email: a.Email, ClosedAt: time.Now()})
	return nil
}

// ApplyInterest pays one day's (today) worth of interest — this is a
// system-triggered method called once a day by the Task Queue batch
// (scheduling.md), not a user command. It computes
// interest = floor(balance * rate), and if it's greater than 0, records a
// Transaction the same way Deposit does (using TransactionTypeInterest to
// distinguish it) and raises an InterestPaid event.
//
// Idempotency is guaranteed two ways:
//  1. If today and LastInterestPaidAt are the same date, treat it as already
//     paid and silently skip (Level 1 — state-based, safe under
//     at-least-once re-execution).
//  2. If the computed interest is 0, state is never changed in the first
//     place (LastInterestPaidAt is not updated either), so re-running any
//     number of times produces the same result — recomputing always yields 0
//     as long as the balance hasn't changed.
//
// If applied is false, nothing changed (it was skipped), and the returned
// Transaction is meaningless in that case.
func (a *Account) ApplyInterest(rate float64, today time.Time) (Transaction, bool, error) {
	if a.Status != StatusActive {
		return Transaction{}, false, ErrInterestRequiresActiveAccount
	}
	if isSameDate(a.LastInterestPaidAt, today) {
		return Transaction{}, false, nil
	}

	interestAmount := int64(float64(a.Balance.Amount) * rate) // integer truncation = floor(balance * rate) (balance >= 0)
	if interestAmount <= 0 {
		return Transaction{}, false, nil
	}

	money, err := NewMoney(interestAmount, a.Balance.Currency)
	if err != nil {
		return Transaction{}, false, err
	}
	newBalance, err := a.Balance.Add(money)
	if err != nil {
		return Transaction{}, false, err
	}
	a.Balance = newBalance
	tx := newTransaction(a.AccountID, TransactionTypeInterest, money, "")
	a.transactions = append(a.transactions, tx)
	a.LastInterestPaidAt = today
	a.events = append(a.events, InterestPaid{
		AccountID:     a.AccountID,
		Email:         a.Email,
		TransactionID: tx.TransactionID,
		Amount:        money,
		BalanceAfter:  a.Balance,
		CreatedAt:     tx.CreatedAt,
	})
	return tx, true, nil
}

// isSameDate reports whether two times fall on the same UTC calendar date.
// If a is a zero time.Time (interest never paid yet), it always returns false.
func isSameDate(a, b time.Time) bool {
	if a.IsZero() {
		return false
	}
	ay, am, ad := a.Date()
	by, bm, bd := b.Date()
	return ay == by && am == bm && ad == bd
}

func (a *Account) DomainEvents() []DomainEvent        { return a.events }
func (a *Account) ClearEvents()                       { a.events = nil }
func (a *Account) PendingTransactions() []Transaction { return a.transactions }
func (a *Account) ClearTransactions()                 { a.transactions = nil }
