package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

type CreatePaymentCommand struct {
	CardID      string
	Amount      int64
	RequesterID string
}

// CreatePaymentHandler pays with a card — it queries the Card BC (card active
// status) and the Account BC (account active status, sufficient balance) via
// synchronous Adapters (ACL) to complete its checks, then creates the Payment
// and immediately marks it completed. It does not debit the account here —
// the PaymentCompleted Domain Event is translated into a
// payment.completed.v1 Integration Event, which the Account BC processes
// asynchronously (cross-domain.md's "synchronous = query, asynchronous =
// state change" principle).
type CreatePaymentHandler struct {
	repo     payment.Repository
	cards    PaymentCardAdapter
	accounts PaymentAccountAdapter
}

func NewCreatePaymentHandler(
	repo payment.Repository,
	cards PaymentCardAdapter,
	accounts PaymentAccountAdapter,
) *CreatePaymentHandler {
	return &CreatePaymentHandler{repo: repo, cards: cards, accounts: accounts}
}

func (h *CreatePaymentHandler) Handle(ctx context.Context, cmd CreatePaymentCommand) (*payment.Payment, error) {
	// Check via a synchronous Adapter (ACL) that the card exists and is active — a synchronous call because the response (whether payment can proceed) depends on it.
	card, err := h.cards.FindCard(ctx, cmd.CardID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if card == nil {
		return nil, payment.ErrLinkedCardNotFound
	}
	if !card.Active {
		return nil, payment.ErrRequiresActiveCard
	}

	// Check via a synchronous Adapter (ACL) that the linked account is active
	// and has sufficient balance (a read-only check). The actual debit does
	// not happen here.
	acc, err := h.accounts.FindAccount(ctx, card.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if acc == nil {
		return nil, payment.ErrLinkedAccountNotFound
	}
	if !acc.Active {
		return nil, payment.ErrRequiresActiveAccount
	}
	if acc.Balance < cmd.Amount {
		return nil, payment.ErrInsufficientBalance
	}

	p := payment.New(cmd.CardID, card.AccountID, cmd.RequesterID, cmd.Amount)
	if err := p.Complete(); err != nil {
		return nil, err
	}

	// Save and return immediately — publishing to/consuming from SQS via the
	// Outbox is solely the responsibility of the independently, periodically
	// running outbox.Poller/outbox.Consumer (no synchronous draining, domain-events.md).
	if err := h.repo.SavePayment(ctx, p); err != nil {
		return nil, err
	}
	return p, nil
}
