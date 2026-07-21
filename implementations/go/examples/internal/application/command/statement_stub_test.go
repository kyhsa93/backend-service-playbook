package command_test

import (
	"context"
	"time"

	"github.com/example/account-service/internal/application/command"
)

// stubPaymentQueryAdapter는 command.PaymentQueryAdapter 포트를 함수 필드로 대체하는
// 최소 mock이다(stubAccountAdapter/stubPaymentCardAdapter와 동일한 관용구).
type stubPaymentQueryAdapter struct {
	summarizeFn func(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error)
}

func (s *stubPaymentQueryAdapter) SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error) {
	if s.summarizeFn == nil {
		return command.CardPaymentSummary{}, nil
	}
	return s.summarizeFn(ctx, cardID, from, to)
}

// stubStatementNotifier는 command.StatementNotifier 포트를 함수 필드로 대체하는
// 최소 mock이다.
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
