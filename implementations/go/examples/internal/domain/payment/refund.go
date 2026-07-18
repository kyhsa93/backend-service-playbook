package payment

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Refund는 환불을 표현하는 별도의 Aggregate Root다. 원 결제(Payment)의 상태·금액에 대한
// 판단은 Refund 자신이 할 수 없다 — EvaluateRefundEligibility(Domain Service, 이 패키지의
// refund_eligibility_service.go)가 Payment+Refund 두 Aggregate를 함께 로드해 조율한 결과
// (RefundDecision)를 받아서만 Approve()/Reject()를 호출한다.
type Refund struct {
	RefundID     string
	PaymentID    string
	Amount       int64
	Reason       string
	Status       RefundStatus
	DecisionNote string
	CreatedAt    time.Time
	events       []DomainEvent
}

// NewRefund는 환불 요청을 REQUESTED 상태로 만드는 순수 생성 팩토리다.
func NewRefund(paymentID string, amount int64, reason string) *Refund {
	return &Refund{
		RefundID:  common.NewID(),
		PaymentID: paymentID,
		Amount:    amount,
		Reason:    reason,
		Status:    RefundStatusRequested,
		CreatedAt: time.Now(),
	}
}

// ReconstituteRefund는 저장소에서 읽은 행을 도메인 객체로 되살린다.
func ReconstituteRefund(refundID, paymentID string, amount int64, reason string, status RefundStatus, decisionNote string, createdAt time.Time) *Refund {
	return &Refund{
		RefundID:     refundID,
		PaymentID:    paymentID,
		Amount:       amount,
		Reason:       reason,
		Status:       status,
		DecisionNote: decisionNote,
		CreatedAt:    createdAt,
	}
}

// Approve는 환불을 승인한다. paymentContext(accountID/ownerID)는 EvaluateRefundEligibility의
// 판단 결과가 아니라, 판단 이후 외부 BC에 전파할 Integration Event를 조립하기 위해 호출부
// (RequestRefundHandler)가 이미 로드해둔 Payment에서 얻어 넘기는 참조 데이터일 뿐이다 —
// Refund 자신의 필드로 승격하지 않는다.
func (r *Refund) Approve(accountID, ownerID string) error {
	if r.Status != RefundStatusRequested {
		return ErrRefundApproveRequiresRequestedRefund
	}
	r.Status = RefundStatusApproved
	r.DecisionNote = "refund approved"
	r.events = append(r.events, RefundApproved{
		RefundID:   r.RefundID,
		PaymentID:  r.PaymentID,
		AccountID:  accountID,
		OwnerID:    ownerID,
		Amount:     r.Amount,
		ApprovedAt: time.Now(),
	})
	return nil
}

// Reject는 환불을 거부한다. RequestRefundHandler는 이 반환값을 에러로 취급하지 않고
// REJECTED 상태로 저장한 Refund를 그대로 응답한다(환불 거부는 도메인 관점에서 유효한
// 상태 전이이지 입력 오류가 아니다) — 어떤 Command도 이 메서드를 에러 취급하지 않는다.
func (r *Refund) Reject(reason string) error {
	if r.Status != RefundStatusRequested {
		return ErrRefundRejectRequiresRequestedRefund
	}
	r.Status = RefundStatusRejected
	r.DecisionNote = reason
	return nil
}

// Complete는 환불을 완료 처리한다. 현재는 refund.approved.v1을 Account BC가 구독해
// 크레딧을 실행하는 것으로 환불 처리가 끝나고, 그 크레딧 성공을 Payment BC로 다시
// 알려주는 콜백 경로가 없다(REST 표면에 없음) — Payment.Fail()과 같은 이유로 미연결
// 상태다. 상태 모델의 완결성을 위해 남겨두고 Domain 단위 테스트로만 검증한다.
func (r *Refund) Complete() error {
	if r.Status != RefundStatusApproved {
		return ErrRefundCompleteRequiresApprovedRefund
	}
	r.Status = RefundStatusCompleted
	return nil
}

func (r *Refund) DomainEvents() []DomainEvent { return r.events }
func (r *Refund) ClearEvents()                { r.events = nil }
