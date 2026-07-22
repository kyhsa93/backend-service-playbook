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

// Handle moves amount from the source account to the target account. The
// target account is looked up without an owner filter — since transferring
// to someone else's account is the point of this feature, only its
// existence and active status need to be checked (ownership verification
// applies only to the source account).
//
// Saving both the source and target Account instances is wrapped into a
// single physical transaction via tx.RunInTx — otherwise a failure mode
// arises where "the withdrawal was applied but the deposit was lost" (see
// TransactionManager, database.Manager).
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

	// transferID does not introduce a new persistent Aggregate dedicated to
	// this transfer — it is used only as the referenceID correlating the two
	// Transaction rows. Since the (reference_id, type) combination is already
	// unique, the source (WITHDRAWAL) and target (DEPOSIT) rows can share the
	// same transferID without colliding. It is used as the raw 32-character
	// value with no suffix — since transactions.reference_id is VARCHAR(36),
	// appending a suffix could exceed that limit.
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
