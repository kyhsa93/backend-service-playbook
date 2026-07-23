package command_test

import (
	"context"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

// stubPaymentStore is a minimal mock satisfying both payment.Repository/Query
// and payment.RefundRepository/Query (the test reuses the same structure as
// infrastructure/persistence/payment_repository.go, which satisfies all four
// interfaces with a single struct).
type stubPaymentStore struct {
	findPaymentsFn            func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error)
	saveFn                    func(ctx context.Context, p *payment.Payment) error
	findRefundsFn             func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error)
	saveRefundFn              func(ctx context.Context, r *payment.Refund) error
	summarizeRefundsByOwnerFn func(ctx context.Context, q payment.RefundSummaryQuery) (payment.RefundSummary, error)
}

func (s *stubPaymentStore) FindPayments(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
	if s.findPaymentsFn == nil {
		return nil, 0, nil
	}
	return s.findPaymentsFn(ctx, q)
}

func (s *stubPaymentStore) SavePayment(ctx context.Context, p *payment.Payment) error {
	if s.saveFn == nil {
		return nil
	}
	return s.saveFn(ctx, p)
}

func (s *stubPaymentStore) FindRefunds(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
	if s.findRefundsFn == nil {
		return nil, 0, nil
	}
	return s.findRefundsFn(ctx, q)
}

func (s *stubPaymentStore) SaveRefund(ctx context.Context, r *payment.Refund) error {
	if s.saveRefundFn == nil {
		return nil
	}
	return s.saveRefundFn(ctx, r)
}

func (s *stubPaymentStore) SummarizeRefundsByOwner(ctx context.Context, q payment.RefundSummaryQuery) (payment.RefundSummary, error) {
	if s.summarizeRefundsByOwnerFn == nil {
		return payment.RefundSummary{}, nil
	}
	return s.summarizeRefundsByOwnerFn(ctx, q)
}

// stubPaymentCardAdapter is a mock that substitutes the command.PaymentCardAdapter port with function fields.
type stubPaymentCardAdapter struct {
	findCardFn func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error)
}

func (s *stubPaymentCardAdapter) FindCard(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
	return s.findCardFn(ctx, cardID, ownerID)
}

// stubPaymentAccountAdapter is a mock that substitutes the command.PaymentAccountAdapter port with function fields.
type stubPaymentAccountAdapter struct {
	findAccountFn func(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error)
}

func (s *stubPaymentAccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
	return s.findAccountFn(ctx, accountID, ownerID)
}

// stubRefundReasonClassifier is a mock that substitutes the command.RefundReasonClassifier
// Technical Service port — tests never call a real LLM (per its own contract, Classify has no
// error return, so this stub can't fail either).
type stubRefundReasonClassifier struct {
	classifyFn func(ctx context.Context, reason string) payment.RefundReasonClassification
}

func (s *stubRefundReasonClassifier) Classify(ctx context.Context, reason string) payment.RefundReasonClassification {
	if s.classifyFn == nil {
		return payment.RefundReasonClassification{Category: payment.RefundReasonOther, FraudRiskScore: 0}
	}
	return s.classifyFn(ctx, reason)
}

// stubRefundFraudRiskScorer is a mock that substitutes the
// command.RefundFraudRiskScorer Technical Service port — tests never call a
// real ML model (per its own contract, Score has no error return, so this
// stub can't fail either).
type stubRefundFraudRiskScorer struct {
	scoreFn func(ctx context.Context, features payment.RefundRiskFeatures) float64
}

func (s *stubRefundFraudRiskScorer) Score(ctx context.Context, features payment.RefundRiskFeatures) float64 {
	if s.scoreFn == nil {
		return 0
	}
	return s.scoreFn(ctx, features)
}
