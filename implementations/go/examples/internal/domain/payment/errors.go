package payment

import "errors"

// 아래 sentinel error는 root docs/architecture/error-handling.md가 요구하는 "에러 코드와
// 에러 메시지 enum이 1:1"이라는 제약의 Go 관용구다(account/errors.go, card/errors.go와 동일한
// 패턴). Payment/Refund 두 Aggregate가 반환할 수 있는 모든 가드 클로즈 에러를 담는다 —
// 일부(Refund*RequiresRequestedRefund, *CompleteRequiresPendingPayment 등)는 현재 REST
// 표면에서 도달하지 않는 방어적 코드다: RefundEligibilityService가 이미 올바른 선행 상태를
// 보장한 뒤에만 Refund.Approve/Reject를 호출하고, Payment.Fail/Refund.Complete는 아직 어떤
// Command도 연결하지 않았기 때문이다(nestjs 레퍼런스와 동일한 이유 — 상태 전이 자체는
// 완결돼 있어야 하므로 Domain 단위 테스트로 검증하고 sentinel error도 미리 갖춰둔다).
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
	ErrCompleteRequiresPendingPayment       = errors.New("payment must be pending to complete")
	ErrFailRequiresPendingPayment           = errors.New("payment must be pending to fail")
	ErrRefundApproveRequiresRequestedRefund = errors.New("refund must be requested to approve")
	ErrRefundRejectRequiresRequestedRefund  = errors.New("refund must be requested to reject")
	ErrRefundCompleteRequiresApprovedRefund = errors.New("refund must be approved to complete")
)
