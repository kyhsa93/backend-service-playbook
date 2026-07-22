package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// WithdrawByPaymentCommand is the input to the use case reacting to the
// Payment BC's payment.completed.v1 Integration Event. ReferenceID is the
// Payment BC's paymentId and is used as the key for the idempotency check
// (Level 2 Ledger).
type WithdrawByPaymentCommand struct {
	AccountID   string
	Amount      int64
	ReferenceID string
}

// WithdrawByPaymentHandler actually performs here the debit that was
// already decided via a synchronous Adapter at payment time — unlike
// WithdrawHandler (a user-initiated direct withdrawal), this reaction
// silently ignores the request if a transaction with the same ReferenceID
// (paymentId) already exists (it must be safe under at-least-once
// redelivery — repeatedly applying a money movement would keep draining the
// balance).
//
// This Handler is always invoked only through outbox.Consumer's handlers
// map (event_type "payment.completed.v1", see main.go) — it does not
// directly reference outbox.Poller/outbox.Consumer. The new Domain Event
// (MoneyWithdrawn) raised by Save() is persisted to the Outbox within the
// same transaction, and the independently, periodically running Poller
// publishes it to SQS on the next tick (no synchronous draining, domain-events.md).
type WithdrawByPaymentHandler struct {
	repo account.Repository
}

func NewWithdrawByPaymentHandler(repo account.Repository) *WithdrawByPaymentHandler {
	return &WithdrawByPaymentHandler{repo: repo}
}

func (h *WithdrawByPaymentHandler) Handle(ctx context.Context, cmd WithdrawByPaymentCommand) error {
	alreadyProcessed, err := h.repo.HasTransactionWithReference(ctx, cmd.ReferenceID, account.TransactionTypeWithdrawal)
	if err != nil {
		return fmt.Errorf("withdraw by payment: %w", err)
	}
	if alreadyProcessed {
		return nil
	}

	accounts, _, err := h.repo.FindAccounts(ctx, account.FindQuery{AccountID: cmd.AccountID, Take: 1})
	if err != nil {
		return fmt.Errorf("withdraw by payment: %w", err)
	}
	if len(accounts) == 0 {
		return nil // Silently ignore if there's no target account to react against (e.g. the account was already deleted).
	}

	a := accounts[0]
	if _, err := a.Withdraw(cmd.Amount, cmd.ReferenceID); err != nil {
		return err
	}
	return h.repo.SaveAccount(ctx, a)
}
