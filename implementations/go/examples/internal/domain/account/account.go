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
	// LastInterestPaidAt은 ApplyInterest가 마지막으로 실제 이자를 지급(잔액 반영)한
	// 날짜다. 값이 없으면(zero time.Time) 아직 한 번도 이자를 받은 적이 없다는 뜻이다.
	// "오늘 이미 이자를 받았는가"를 이 필드 하나로 판단할 수 있어(Level 1 — 본질적
	// 멱등), 같은 날짜의 배치 Task가 at-least-once로 재실행돼도 별도 Ledger 없이
	// 자연스러운 no-op이 된다(docs/architecture/domain-events.md의 멱등성 3단계).
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

// ApplyInterest는 today 하루치 이자를 지급한다 — 사용자 커맨드가 아니라 Task Queue
// 배치(scheduling.md)가 매일 한 번 호출하는 시스템 기동 메서드다. interest =
// floor(balance * rate)를 계산해 0보다 크면 Deposit과 동일한 방식으로 Transaction을
// 남기고(구분을 위해 TransactionTypeInterest) InterestPaid 이벤트를 발생시킨다.
//
// 멱등성은 두 겹으로 보장된다:
//  1. today와 LastInterestPaidAt이 같은 날짜면 이미 지급된 것으로 보고 조용히
//     스킵한다(Level 1 — 상태 기반, at-least-once 재실행에 안전).
//  2. 계산된 이자가 0이면 애초에 상태를 바꾸지 않으므로(LastInterestPaidAt도 갱신하지
//     않는다) 몇 번을 다시 실행해도 결과가 같다 — 잔액이 바뀌지 않는 한 재계산해도
//     항상 0이기 때문이다.
//
// applied가 false면 아무 것도 바뀌지 않았다는 뜻이고(스킵), 이때 반환하는 Transaction은
// 의미가 없다.
func (a *Account) ApplyInterest(rate float64, today time.Time) (Transaction, bool, error) {
	if a.Status != StatusActive {
		return Transaction{}, false, ErrInterestRequiresActiveAccount
	}
	if isSameDate(a.LastInterestPaidAt, today) {
		return Transaction{}, false, nil
	}

	interestAmount := int64(float64(a.Balance.Amount) * rate) // 정수 절삭 = floor(balance * rate) (balance >= 0)
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

// isSameDate는 두 시각이 같은 UTC 달력 날짜인지 본다. a가 zero time.Time이면(아직 이자를
// 받은 적 없음) 항상 false다.
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
