package command_test

import (
	"context"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

// stubPaymentStore는 payment.Repository/Query와 payment.RefundRepository/Query를 모두
// 만족하는 최소 mock이다(infrastructure/persistence/payment_repository.go가 한 struct로
// 네 인터페이스를 모두 만족하는 것과 동일한 구조를 테스트에서도 재사용한다).
type stubPaymentStore struct {
	findPaymentsFn func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error)
	saveFn         func(ctx context.Context, p *payment.Payment) error
	findRefundsFn  func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error)
	saveRefundFn   func(ctx context.Context, r *payment.Refund) error
}

func (s *stubPaymentStore) FindPayments(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
	if s.findPaymentsFn == nil {
		return nil, 0, nil
	}
	return s.findPaymentsFn(ctx, q)
}

func (s *stubPaymentStore) Save(ctx context.Context, p *payment.Payment) error {
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

// stubPaymentCardAdapter는 command.PaymentCardAdapter 포트를 함수 필드로 대체하는 mock이다.
type stubPaymentCardAdapter struct {
	findCardFn func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error)
}

func (s *stubPaymentCardAdapter) FindCard(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
	return s.findCardFn(ctx, cardID, ownerID)
}

// stubPaymentAccountAdapter는 command.PaymentAccountAdapter 포트를 함수 필드로 대체하는 mock이다.
type stubPaymentAccountAdapter struct {
	findAccountFn func(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error)
}

func (s *stubPaymentAccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
	return s.findAccountFn(ctx, accountID, ownerID)
}
