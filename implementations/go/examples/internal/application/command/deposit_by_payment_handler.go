package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// DepositByPaymentCommand는 Payment BC의 payment.cancelled.v1(결제취소 보상 크레딧) 및
// refund.approved.v1(환불 승인 크레딧) Integration Event 둘 다에 대한 반응 유스케이스의
// 입력이다 — 두 이벤트는 "이미 차감된 금액을 되돌린다"는 동일한 동작이고 ReferenceID
// (paymentId 또는 refundId)만 다르므로 커맨드를 하나로 재사용한다.
type DepositByPaymentCommand struct {
	AccountID   string
	Amount      int64
	ReferenceID string
}

// DepositByPaymentHandler의 멱등성은 WithdrawByPaymentHandler와 동일한 이유로 Level 2
// Ledger를 쓴다. OutboxRelay를 주입받지 않는 이유도 WithdrawByPaymentHandler와 동일하다
// (항상 이미 진행 중인 outbox.Relay.ProcessPending 호출 안에서만 실행되므로, 새로 적재된
// Domain Event는 그 호출의 다음 패스가 자동으로 드레인한다).
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
	return h.repo.Save(ctx, a)
}
