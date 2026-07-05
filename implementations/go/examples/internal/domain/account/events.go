package account

import "time"

type DomainEvent interface {
	isAccountDomainEvent()
}

type AccountCreated struct {
	AccountID string
	OwnerID   string
	Email     string
	Currency  string
	CreatedAt time.Time
}

func (AccountCreated) isAccountDomainEvent() {}

type MoneyDeposited struct {
	AccountID     string
	Email         string
	TransactionID string
	Amount        Money
	BalanceAfter  Money
	CreatedAt     time.Time
}

func (MoneyDeposited) isAccountDomainEvent() {}

type MoneyWithdrawn struct {
	AccountID     string
	Email         string
	TransactionID string
	Amount        Money
	BalanceAfter  Money
	CreatedAt     time.Time
}

func (MoneyWithdrawn) isAccountDomainEvent() {}

type AccountSuspended struct {
	AccountID   string
	Email       string
	SuspendedAt time.Time
}

func (AccountSuspended) isAccountDomainEvent() {}

type AccountReactivated struct {
	AccountID     string
	Email         string
	ReactivatedAt time.Time
}

func (AccountReactivated) isAccountDomainEvent() {}

type AccountClosed struct {
	AccountID string
	Email     string
	ClosedAt  time.Time
}

func (AccountClosed) isAccountDomainEvent() {}
