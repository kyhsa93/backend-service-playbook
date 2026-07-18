package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

type RequestRefundCommand struct {
	PaymentID   string
	Amount      int64
	Reason      string
	RequesterID string
}

// RequestRefundHandler는 환불을 요청한다. 어느 한 Aggregate만으로는 내릴 수 없는 판단
// (원 결제 상태 + 환불 금액 비교)을 Payment+Refund 두 Aggregate를 함께 로드한 이 Handler가
// payment.EvaluateRefundEligibility(Domain Service)에 위임해 조율한다.
//
// 환불 거부는 에러가 아니라 유효한 도메인 결과다 — 이 Handler는 거부돼도 에러를 반환하지
// 않고 REJECTED 상태로 저장한 Refund를 그대로 반환한다. Interface 레이어가 이를 에러가
// 아닌 201 + status:REJECTED로 응답한다.
type RequestRefundHandler struct {
	payments payment.Repository
	refunds  payment.RefundRepository
	outbox   OutboxRelay
}

func NewRequestRefundHandler(
	payments payment.Repository,
	refunds payment.RefundRepository,
	outbox OutboxRelay,
) *RequestRefundHandler {
	return &RequestRefundHandler{payments: payments, refunds: refunds, outbox: outbox}
}

func (h *RequestRefundHandler) Handle(ctx context.Context, cmd RequestRefundCommand) (*payment.Refund, error) {
	p, err := payment.FindOne(ctx, h.payments, cmd.PaymentID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}

	r := payment.NewRefund(p.PaymentID, cmd.Amount, cmd.Reason)

	decision := payment.EvaluateRefundEligibility(p, r)
	if decision.Approved {
		if err := r.Approve(p.AccountID, p.OwnerID); err != nil {
			return nil, err
		}
	} else {
		reason := decision.Reason
		if reason == "" {
			reason = "refund request rejected"
		}
		if err := r.Reject(reason); err != nil {
			return nil, err
		}
	}

	if err := h.refunds.SaveRefund(ctx, r); err != nil {
		return nil, err
	}
	// RefundApproved → refund.approved.v1을 Account BC가 구독해 환불 크레딧을 실행한다.
	// 거부된 경우에는 Domain Event가 없으므로 드레인할 것이 없어 no-op이다.
	if err := h.outbox.ProcessPending(ctx); err != nil {
		return nil, err
	}
	return r, nil
}
