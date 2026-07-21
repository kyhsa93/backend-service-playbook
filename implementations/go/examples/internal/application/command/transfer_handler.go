package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/common"
	"github.com/example/account-service/internal/domain/account"
)

type TransferCommand struct {
	SourceAccountID string
	TargetAccountID string
	RequesterID     string
	Amount          int64
}

type TransferResult struct {
	TransferID        string
	SourceTransaction account.Transaction
	TargetTransaction account.Transaction
}

type TransferHandler struct {
	repo account.Repository
	tx   TransactionManager
}

func NewTransferHandler(repo account.Repository, tx TransactionManager) *TransferHandler {
	return &TransferHandler{repo: repo, tx: tx}
}

// Handle은 출금 계좌 → 입금 계좌로 amount를 옮긴다. 입금 계좌는 소유자 필터 없이
// 조회한다 — 타인 계좌로 송금하는 것이 이 기능의 목적이라, 존재+활성 여부만 확인하면
// 된다(소유권 확인은 출금 계좌에만 적용).
//
// 출금+입금 두 Account 인스턴스 저장은 tx.RunInTx로 하나의 물리 트랜잭션에 묶인다 —
// 그렇지 않으면 "출금은 반영됐는데 입금은 유실됨" 실패 모드가 생긴다(TransactionManager,
// database.Manager 참고).
func (h *TransferHandler) Handle(ctx context.Context, cmd TransferCommand) (*TransferResult, error) {
	source, err := account.FindOne(ctx, h.repo, cmd.SourceAccountID, cmd.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("transfer: %w", err)
	}
	target, err := account.FindOne(ctx, h.repo, cmd.TargetAccountID, "")
	if err != nil {
		return nil, fmt.Errorf("transfer: %w", err)
	}

	decision := account.EvaluateTransferEligibility(source, target, cmd.Amount)
	if !decision.Approved {
		return nil, decision.Err
	}

	// transferID는 이 송금 전용의 새 영속 Aggregate를 두지 않고, 두 Transaction 행을
	// 상관관계 짓는 referenceID로만 쓴다 — (reference_id, type) 조합이 이미 유니크하므로
	// source(WITHDRAWAL)/target(DEPOSIT) 두 행이 같은 transferID를 공유해도 충돌하지
	// 않는다. 접미사 없이 32자리 원본 그대로 쓴다 — transactions.reference_id가
	// VARCHAR(36)이므로 접미사를 붙이면 그 한도를 넘길 수 있다.
	transferID := common.NewID()
	sourceTx, err := source.Withdraw(cmd.Amount, transferID)
	if err != nil {
		return nil, err
	}
	targetTx, err := target.Deposit(cmd.Amount, transferID)
	if err != nil {
		return nil, err
	}

	if err := h.tx.RunInTx(ctx, func(ctx context.Context) error {
		if err := h.repo.SaveAccount(ctx, source); err != nil {
			return err
		}
		return h.repo.SaveAccount(ctx, target)
	}); err != nil {
		return nil, err
	}

	return &TransferResult{TransferID: transferID, SourceTransaction: sourceTx, TargetTransaction: targetTx}, nil
}
