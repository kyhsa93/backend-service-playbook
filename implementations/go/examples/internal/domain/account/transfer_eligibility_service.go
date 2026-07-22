package account

// TransferDecision is the judgment result of EvaluateTransferEligibility. On
// rejection, Err is exactly the same sentinel error that would occur if the
// user had called Withdraw/Deposit directly — unlike Refund, a Transfer has
// no persistent Aggregate of its own (there's nothing to store the rejection
// in), so a rejection must translate immediately into the caller returning
// an error, and that error must be indistinguishable from a direct call as
// far as the client is concerned.
type TransferDecision struct {
	Approved bool
	Err      error
}

// EvaluateTransferEligibility is "pure domain logic that coordinates
// multiple Aggregates," as defined by the root
// docs/architecture/domain-service.md — expressed as a stateless package
// function for the same reason as EvaluateRefundEligibility.
//
// The judgment "are the source and target accounts different, are both
// active, do the currencies match, and does the source account have a
// sufficient balance" cannot be made from either Account alone — both
// Aggregate instances must be loaded and compared side by side.
func EvaluateTransferEligibility(source, target *Account, amount int64) TransferDecision {
	if source.AccountID == target.AccountID {
		return TransferDecision{Approved: false, Err: ErrTransferSameAccount}
	}
	if source.Status != StatusActive {
		return TransferDecision{Approved: false, Err: ErrWithdrawRequiresActiveAccount}
	}
	if target.Status != StatusActive {
		return TransferDecision{Approved: false, Err: ErrDepositRequiresActiveAccount}
	}
	if source.Balance.Currency != target.Balance.Currency {
		return TransferDecision{Approved: false, Err: ErrCurrencyMismatch}
	}
	if source.Balance.Amount < amount {
		return TransferDecision{Approved: false, Err: ErrInsufficientBalance}
	}
	return TransferDecision{Approved: true}
}
