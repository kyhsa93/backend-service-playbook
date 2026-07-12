// Package acl은 Card BC가 다른 Bounded Context(Account)를 동기 호출할 때 쓰는
// Anticorruption Layer 구현체를 담는다. 인터페이스(command.AccountAdapter)는 호출하는 쪽
// (Card)의 Application 레이어에 있고, 이 구현체는 호출하는 쪽의 Infrastructure에 두어
// Account 도메인을 import한다 — 의존 방향은 "Card Infrastructure → Account 도메인"이며
// Card의 Application/Domain은 Account를 전혀 모른다(cross-domain.md).
package acl

import (
	"context"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

// AccountAdapter는 command.AccountAdapter를 만족하는 ACL 구현체다. Account BC가 노출하는
// 읽기 인터페이스(account.Query)를 호출하고, Account의 모델·에러를 Card가 이해하는 최소 형태
// (command.AccountView)로 번역한다. Account의 Repository/도메인 객체를 직접 참조하지 않는다.
type AccountAdapter struct {
	accounts account.Query
}

func NewAccountAdapter(accounts account.Query) *AccountAdapter {
	return &AccountAdapter{accounts: accounts}
}

var _ command.AccountAdapter = (*AccountAdapter)(nil)

func (a *AccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
	acc, err := a.accounts.FindByID(ctx, accountID, ownerID)
	if err != nil {
		// 상류의 "계좌 없음" 에러 타입을 Card 도메인으로 누수시키지 않고 nil 신호로 번역한다.
		if errors.Is(err, account.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("account adapter find account: %w", err)
	}
	// Account의 Status enum을 노출하지 않고 Card가 필요로 하는 active bool로만 번역한다.
	return &command.AccountView{
		AccountID: acc.AccountID,
		Active:    acc.Status == account.StatusActive,
	}, nil
}
