package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/example/account-service/internal/domain/account"
)

// CategorizeTransactionEventHandler reacts to MoneyWithdrawn (registered in
// main.go alongside MoneyWithdrawnEventHandler for the same eventType —
// outbox.Consumer supports multiple handlers per event type, see
// internal/infrastructure/outbox/consumer.go) to categorize the
// transaction's merchant name asynchronously, off the money-movement hot
// path — the same reasoning WithdrawHandler never calls an LLM directly.
// Inherently Level-1 idempotent (see docs/architecture/domain-events.md): a
// retried delivery just re-runs the same find→categorize→save cycle,
// landing on the same (or an equally acceptable) category.
type CategorizeTransactionEventHandler struct {
	categorizer TransactionAutoCategorizer
	repo        account.TransactionRepository
}

func NewCategorizeTransactionEventHandler(categorizer TransactionAutoCategorizer, repo account.TransactionRepository) *CategorizeTransactionEventHandler {
	return &CategorizeTransactionEventHandler{categorizer: categorizer, repo: repo}
}

// Handle satisfies the outbox.Handler signature — it is invoked whenever the
// Consumer encounters an event_type="MoneyWithdrawn" message, alongside
// MoneyWithdrawnEventHandler.Handle.
func (h *CategorizeTransactionEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.MoneyWithdrawn
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal MoneyWithdrawn: %w", err)
	}

	// Nothing to classify — the requester didn't attach a merchant name to
	// this withdrawal.
	if evt.MerchantName == "" {
		return nil
	}

	tx, err := h.repo.FindTransaction(ctx, evt.TransactionID)
	if err != nil {
		return fmt.Errorf("find transaction: %w", err)
	}
	if tx == nil {
		return nil
	}

	category := h.categorizer.Categorize(ctx, evt.MerchantName, evt.Amount.Amount)
	if err := h.repo.SaveTransaction(ctx, tx.Categorize(category)); err != nil {
		return fmt.Errorf("save transaction: %w", err)
	}
	slog.InfoContext(ctx, "transaction categorized", "transaction_id", evt.TransactionID, "category", category)
	return nil
}
