package payment

// RefundDecision은 EvaluateRefundEligibility의 판단 결과다. Reason은 거부 사유로만
// 쓰인다(승인 시에는 비워둔다).
type RefundDecision struct {
	Approved bool
	Reason   string
}

// EvaluateRefundEligibility는 root docs/architecture/domain-service.md가 정의하는
// "여러 Aggregate를 조율하는 순수 도메인 로직"의 실제 예시다 — 프레임워크 의존성이 없는
// 평범한 패키지 함수로 표현한다(Go에는 DI 컨테이너가 없고 이 판단은 상태가 없으므로,
// 상태 없는 구조체+메서드보다 자유 함수가 더 idiomatic하다).
//
// "원 결제가 COMPLETED 상태여야 하고, 환불 금액이 결제 금액을 넘을 수 없다"는 판단은
// Payment 혼자서도, Refund 혼자서도 내릴 수 없다 — Payment는 자신에 대한 환불 시도를
// 모르고(환불은 Refund Aggregate로만 존재), Refund는 원 결제의 금액·상태를 모른다
// (PaymentID로 참조만 한다). 이 판단을 내리려면 두 Aggregate를 모두 로드해 같은 자리에서
// 비교해야 하므로, 어느 한쪽 Aggregate의 메서드로 넣을 수 없다(넣는다면 다른 쪽 Aggregate
// 전체를 파라미터로 받아야 해 경계가 무너진다) — 그래서 별도의 Domain Service 함수로 둔다.
// 호출부(RequestRefundHandler)가 두 Repository로 각각 로드한 뒤 이 함수를 호출하고,
// 승인되면 refund.Approve(...)를, 거부되면 refund.Reject(...)를 호출한다.
func EvaluateRefundEligibility(p *Payment, r *Refund) RefundDecision {
	if p.Status != StatusCompleted {
		return RefundDecision{Approved: false, Reason: ErrRefundRequiresCompletedPayment.Error()}
	}
	if r.Amount > p.Amount {
		return RefundDecision{Approved: false, Reason: ErrRefundAmountExceedsPayment.Error()}
	}
	return RefundDecision{Approved: true}
}
