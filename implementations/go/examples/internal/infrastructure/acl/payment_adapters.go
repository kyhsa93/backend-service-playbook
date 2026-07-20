// Payment BC가 다른 두 Bounded Context(Card, Account)를 동기 호출할 때 쓰는
// Anticorruption Layer 구현체다. 인터페이스(command.PaymentCardAdapter/
// command.PaymentAccountAdapter)는 호출하는 쪽(Payment)의 Application 레이어에 있고,
// 이 구현체는 호출하는 쪽의 Infrastructure에 두어 Card/Account 도메인을 import한다 —
// 의존 방향은 "Payment Infrastructure → Card/Account 도메인"이며 Payment의
// Application/Domain은 Card/Account를 전혀 모른다(cross-domain.md, account_adapter.go와
// 동일한 패턴).
package acl

import (
	"context"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/domain/card"
)

// PaymentCardAdapter는 command.PaymentCardAdapter를 만족하는 ACL 구현체다. Card BC가
// 노출하는 읽기 인터페이스(card.Query)를 호출하고, Card의 모델·에러를 Payment가 이해하는
// 최소 형태(command.PaymentCardView)로 번역한다.
type PaymentCardAdapter struct {
	cards card.Query
}

func NewPaymentCardAdapter(cards card.Query) *PaymentCardAdapter {
	return &PaymentCardAdapter{cards: cards}
}

var _ command.PaymentCardAdapter = (*PaymentCardAdapter)(nil)

func (a *PaymentCardAdapter) FindCard(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
	c, err := card.FindOne(ctx, a.cards, cardID, ownerID)
	if err != nil {
		// 상류의 "카드 없음" 에러 타입을 Payment 도메인으로 누수시키지 않고 nil 신호로 번역한다.
		if errors.Is(err, card.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("payment card adapter find card: %w", err)
	}
	return &command.PaymentCardView{
		CardID:    c.CardID,
		AccountID: c.AccountID,
		Active:    c.Status == card.StatusActive,
	}, nil
}

// PaymentAccountAdapter는 command.PaymentAccountAdapter를 만족하는 ACL 구현체다.
// Account BC가 노출하는 읽기 인터페이스(account.Query)를 호출하고, Account의 모델·에러를
// Payment가 이해하는 최소 형태(command.PaymentAccountView)로 번역한다.
type PaymentAccountAdapter struct {
	accounts account.Query
}

func NewPaymentAccountAdapter(accounts account.Query) *PaymentAccountAdapter {
	return &PaymentAccountAdapter{accounts: accounts}
}

var _ command.PaymentAccountAdapter = (*PaymentAccountAdapter)(nil)

func (a *PaymentAccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
	acc, err := account.FindOne(ctx, a.accounts, accountID, ownerID)
	if err != nil {
		if errors.Is(err, account.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("payment account adapter find account: %w", err)
	}
	return &command.PaymentAccountView{
		AccountID: acc.AccountID,
		Active:    acc.Status == account.StatusActive,
		Balance:   acc.Balance.Amount,
		Currency:  acc.Balance.Currency,
	}, nil
}
