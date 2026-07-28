package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/example/account-service/internal/domain/account"
)

// historyWindow bounds how many of the account's own most recent
// (excluding this one) withdrawals account.IsWithdrawalAnomalous trains
// its mean/stddev against.
const historyWindow = 30

// AnomalyNotifier is the minimal port DetectWithdrawalAnomalyEventHandler
// needs to send the alert email. Its signature is composed only of
// primitive types, the same reasoning as command.StatementNotifier: the
// alert isn't a reaction to a state change the Account Aggregate itself
// raised (unlike Notifier's account.DomainEvent shape) — it's a judgment
// this handler itself makes. The implementation is handled by
// infrastructure/notification.Service, reusing the existing SES delivery
// path.
type AnomalyNotifier interface {
	NotifyWithdrawalAnomaly(ctx context.Context, accountID, recipient string, amount int64, currency string) error
}

// DetectWithdrawalAnomalyEventHandler reacts to MoneyWithdrawn (registered
// in main.go as a 3rd subscriber alongside MoneyWithdrawnEventHandler and
// CategorizeTransactionEventHandler — outbox.Consumer supports multiple
// handlers per event type, see internal/infrastructure/outbox/consumer.go)
// to flag a withdrawal that's a statistical outlier against the account's
// own history via the account.IsWithdrawalAnomalous Domain Service.
// Deliberately only ever sends a notification — it never blocks, reverses,
// or judges the withdrawal itself (the withdrawal already completed before
// this even runs). This is the design constraint that keeps it out of the
// domain-purity trap the earlier RefundFraudRiskScorer/
// RefundReasonClassifier fell into (both removed — see root
// docs/architecture/domain-service.md's Domain Service section): a signal
// that only ever informs a human, never one a Domain Service treats as a
// judgment input.
type DetectWithdrawalAnomalyEventHandler struct {
	repo     account.Query
	notifier AnomalyNotifier
}

func NewDetectWithdrawalAnomalyEventHandler(repo account.Query, notifier AnomalyNotifier) *DetectWithdrawalAnomalyEventHandler {
	return &DetectWithdrawalAnomalyEventHandler{repo: repo, notifier: notifier}
}

// Handle satisfies the outbox.Handler signature — it is invoked whenever
// the Consumer encounters an event_type="MoneyWithdrawn" message, alongside
// MoneyWithdrawnEventHandler.Handle and CategorizeTransactionEventHandler.Handle.
func (h *DetectWithdrawalAnomalyEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.MoneyWithdrawn
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal MoneyWithdrawn: %w", err)
	}

	history, err := h.repo.FindRecentWithdrawalAmounts(ctx, evt.AccountID, evt.TransactionID, historyWindow)
	if err != nil {
		return fmt.Errorf("find recent withdrawal amounts: %w", err)
	}
	if !account.IsWithdrawalAnomalous(history, evt.Amount.Amount) {
		return nil
	}

	if err := h.notifier.NotifyWithdrawalAnomaly(ctx, evt.AccountID, evt.Email, evt.Amount.Amount, evt.Amount.Currency); err != nil {
		return fmt.Errorf("notify withdrawal anomaly: %w", err)
	}
	slog.InfoContext(ctx, "anomalous withdrawal detected, alert sent",
		"account_id", evt.AccountID, "transaction_id", evt.TransactionID, "amount", evt.Amount.Amount)
	return nil
}
