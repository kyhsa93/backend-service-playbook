package account

import (
	"time"

	"github.com/example/account-service/internal/common"
)

type Account struct {
	AccountID    string
	OwnerID      string
	Email        string
	Balance      Money
	Status       Status
	CreatedAt    time.Time
	UpdatedAt    time.Time
	events       []DomainEvent
	transactions []Transaction
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

func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt time.Time) *Account {
	return &Account{
		AccountID: accountID,
		OwnerID:   ownerID,
		Email:     email,
		Balance:   balance,
		Status:    status,
		CreatedAt: createdAt,
		UpdatedAt: updatedAt,
	}
}

// Deposit은 입금을 처리한다. referenceID는 사용자가 직접 요청한 입금이면 빈 문자열이고,
// 외부 BC(Payment)의 Integration Event에 대한 반응(보상 크레딧/환불 크레딧)이면 그 BC의
// Aggregate ID(paymentId/refundId)다 — 호출부(Application Handler)가 Repository의
// HasTransactionWithReference로 멱등성을 먼저 확인한 뒤 이 메서드를 호출할 책임을 진다.
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

// Withdraw는 출금을 처리한다. referenceID는 Deposit과 동일한 규칙을 따른다 — 사용자가
// 직접 요청한 출금이면 빈 문자열, Payment BC의 payment.completed.v1에 대한 반응(실제
// 차감)이면 paymentId다.
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

func (a *Account) DomainEvents() []DomainEvent        { return a.events }
func (a *Account) ClearEvents()                       { a.events = nil }
func (a *Account) PendingTransactions() []Transaction { return a.transactions }
func (a *Account) ClearTransactions()                 { a.transactions = nil }
