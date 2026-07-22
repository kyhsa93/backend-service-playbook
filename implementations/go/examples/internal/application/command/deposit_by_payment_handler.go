package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// DepositByPaymentCommand is the input to the use case reacting to both of
// the Payment BC's payment.cancelled.v1 (payment-cancellation compensating
// credit) and refund.approved.v1 (refund-approval credit) Integration
// Events — both events perform the same action of "reversing an amount
// already debited," differing only in ReferenceID (paymentId or refundId),
// so a single command is reused for both.
type DepositByPaymentCommand struct {
	AccountID   string
	Amount      int64
	ReferenceID string
}

// DepositByPaymentHandler uses a Level 2 Ledger for idempotency for the same
// reason as WithdrawByPaymentHandler. It also does not directly reference
// outbox.Poller/outbox.Consumer for the same reason as
// WithdrawByPaymentHandler (it is always invoked only through
// outbox.Consumer's handlers map, and newly persisted Domain Events are
// drained on the next tick by the independently, periodically running Poller).
type DepositByPaymentHandler struct {
	repo account.Repository
}

func NewDepositByPaymentHandler(repo account.Repository) *DepositByPaymentHandler {
	return &DepositByPaymentHandler{repo: repo}
}

func (h *DepositByPaymentHandler) Handle(ctx context.Context, cmd DepositByPaymentCommand) error {
	alreadyProcessed, err := h.repo.HasTransactionWithReference(ctx, cmd.ReferenceID, account.TransactionTypeDeposit)
	if err != nil {
		return fmt.Errorf("deposit by payment: %w", err)
	}
	if alreadyProcessed {
		return nil
	}

	accounts, _, err := h.repo.FindAccounts(ctx, account.FindQuery{AccountID: cmd.AccountID, Take: 1})
	if err != nil {
		return fmt.Errorf("deposit by payment: %w", err)
	}
	if len(accounts) == 0 {
		return nil
	}

	a := accounts[0]
	if _, err := a.Deposit(cmd.Amount, cmd.ReferenceID); err != nil {
		return err
	}
	return h.repo.SaveAccount(ctx, a)
}
