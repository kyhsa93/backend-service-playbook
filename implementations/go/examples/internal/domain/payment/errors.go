package payment

import "errors"

// The sentinel errors below are the Go idiom for the constraint required by
// the root docs/architecture/error-handling.md that "error codes and the
// error message enum are 1:1" (same pattern as account/errors.go,
// card/errors.go). They hold every guard-clause error the two Payment/Refund
// Aggregates can return — some (Refund*RequiresRequestedRefund,
// *CompleteRequiresPendingPayment, etc.) are defensive code that the current
// REST surface never reaches: RefundEligibilityService only calls
// Refund.Approve/Reject after it has already guaranteed the correct
// preceding state, and Payment.Fail/Refund.Complete aren't wired to any
// Command yet (same reasoning as the nestjs reference — the state
// transitions themselves must still be complete, so they're verified with
// Domain unit tests and their sentinel errors are prepared in advance).
var (
	ErrNotFound                             = errors.New("payment not found")
	ErrLinkedCardNotFound                   = errors.New("linked card not found")
	ErrRequiresActiveCard                   = errors.New("card must be active to create a payment")
	ErrLinkedAccountNotFound                = errors.New("linked account not found")
	ErrRequiresActiveAccount                = errors.New("account must be active to create a payment")
	ErrInsufficientBalance                  = errors.New("insufficient balance to create a payment")
	ErrCancelRequiresCompletedPayment       = errors.New("payment must be completed to cancel")
	ErrRefundRequiresCompletedPayment       = errors.New("refund requires a completed payment")
	ErrRefundAmountExceedsPayment           = errors.New("refund amount must not exceed payment amount")
	ErrRefundFlaggedHighFraudRisk           = errors.New("refund reason was flagged as high fraud risk and requires manual review")
	ErrRefundPatternFlaggedHighRisk         = errors.New("refund pattern was flagged as high risk by the fraud-risk model and requires manual review")
	ErrCompleteRequiresPendingPayment       = errors.New("payment must be pending to complete")
	ErrFailRequiresPendingPayment           = errors.New("payment must be pending to fail")
	ErrRefundApproveRequiresRequestedRefund = errors.New("refund must be requested to approve")
	ErrRefundRejectRequiresRequestedRefund  = errors.New("refund must be requested to reject")
	ErrRefundCompleteRequiresApprovedRefund = errors.New("refund must be approved to complete")
)
