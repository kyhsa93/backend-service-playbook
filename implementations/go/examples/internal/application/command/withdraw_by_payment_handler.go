package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// WithdrawByPaymentCommand는 Payment BC의 payment.completed.v1 Integration Event에 대한
// 반응 유스케이스의 입력이다. ReferenceID는 Payment BC의 paymentId이며 멱등성 판단
// (Level 2 Ledger)의 키로 쓰인다.
type WithdrawByPaymentCommand struct {
	AccountID   string
	Amount      int64
	ReferenceID string
}

// WithdrawByPaymentHandler는 결제 시점에 이미 동기 Adapter로 판정된 차감을 여기서 실제로
// 수행한다 — WithdrawHandler(사용자 직접 출금)와 달리 이 반응은 같은 ReferenceID
// (paymentId)의 거래가 이미 있으면 조용히 무시한다(at-least-once 재수신에 안전해야
// 하므로 — 금액 이동은 반복 적용하면 잔액이 계속 줄어든다).
//
// OutboxRelay를 주입받지 않는다 — 이 Handler는 항상 outbox.Relay.ProcessPending의 핸들러
// map을 통해서만 호출된다("payment.completed.v1" event_type, main.go 참고). Save()가
// 발생시키는 새 Domain Event(MoneyWithdrawn)는 같은 트랜잭션으로 Outbox에 함께 적재되고,
// 이미 진행 중인 그 ProcessPending 호출의 다음 패스가 자동으로 이어서 드레인한다
// (outbox/relay.go의 다중 패스 루프 참고) — 스스로 ProcessPending을 다시 호출하면
// main()의 조립 시점에 순환 의존을 만들 뿐 실질적인 이득이 없다.
type WithdrawByPaymentHandler struct {
	repo account.Repository
}

func NewWithdrawByPaymentHandler(repo account.Repository) *WithdrawByPaymentHandler {
	return &WithdrawByPaymentHandler{repo: repo}
}

func (h *WithdrawByPaymentHandler) Handle(ctx context.Context, cmd WithdrawByPaymentCommand) error {
	alreadyProcessed, err := h.repo.HasTransactionWithReference(ctx, cmd.ReferenceID, account.TransactionTypeWithdrawal)
	if err != nil {
		return fmt.Errorf("withdraw by payment: %w", err)
	}
	if alreadyProcessed {
		return nil
	}

	accounts, _, err := h.repo.FindAccounts(ctx, account.FindQuery{AccountID: cmd.AccountID, Take: 1})
	if err != nil {
		return fmt.Errorf("withdraw by payment: %w", err)
	}
	if len(accounts) == 0 {
		return nil // 반응할 대상 계좌가 없으면 조용히 무시한다(예: 계좌가 이미 삭제됨).
	}

	a := accounts[0]
	if _, err := a.Withdraw(cmd.Amount, cmd.ReferenceID); err != nil {
		return err
	}
	return h.repo.Save(ctx, a)
}
