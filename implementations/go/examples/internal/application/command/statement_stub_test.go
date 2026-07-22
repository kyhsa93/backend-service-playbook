package command_test

import (
	"context"
	"time"

	"github.com/example/account-service/internal/application/command"
)

// stubPaymentQueryAdapter is a minimal mock that substitutes the
// command.PaymentQueryAdapter port with function fields (same idiom as
// stubAccountAdapter/stubPaymentCardAdapter).
type stubPaymentQueryAdapter struct {
	summarizeFn func(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error)
}

func (s *stubPaymentQueryAdapter) SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error) {
	if s.summarizeFn == nil {
		return command.CardPaymentSummary{}, nil
	}
	return s.summarizeFn(ctx, cardID, from, to)
}

// stubStatementNotifier is a minimal mock that substitutes the
// command.StatementNotifier port with function fields.
type stubStatementNotifier struct {
	notifyFn func(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error
}

func (s *stubStatementNotifier) NotifyCardStatement(
	ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64,
) error {
	if s.notifyFn == nil {
		return nil
	}
	return s.notifyFn(ctx, accountID, recipient, cardID, period, paymentCount, totalAmount)
}
