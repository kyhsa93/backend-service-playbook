package payment

// RefundDecision is the judgment result of EvaluateRefundEligibility. Reason
// is used only as the rejection reason (left empty on approval).
type RefundDecision struct {
	Approved bool
	Reason   string
}

// mlFraudRiskRejectionThreshold — the fraud-risk score is produced upstream
// by command.RefundFraudRiskScorer (a Technical Service trained on the
// requester's own refund/payment history — refund count, rejection count,
// amount ratio, minutes since payment — see
// infrastructure/ml/refund_fraud_risk_scorer_native.go /
// refund_fraud_risk_scorer_http.go). This Domain Service never calls the
// model itself and doesn't know how the score was computed; it only receives
// the already-computed float64 as one more plain input alongside
// Payment/Refund, and applies its own fixed threshold. The scorer supplies a
// signal; this function still owns the actual approve/reject judgment.
const mlFraudRiskRejectionThreshold = 0.8

// EvaluateRefundEligibility is a concrete example of "pure domain logic
// that coordinates multiple Aggregates," as defined by the root
// docs/architecture/domain-service.md — expressed as a plain package
// function with no framework dependency (Go has no DI container, and since
// this judgment is stateless, a free function is more idiomatic than a
// stateless struct + method).
//
// The judgment "the original payment must be COMPLETED, and the refund
// amount must not exceed the payment amount" cannot be made by Payment
// alone or by Refund alone — Payment doesn't know about refund attempts
// against it (a refund exists only as a Refund Aggregate), and Refund
// doesn't know the original payment's amount or status (it only references
// it via PaymentID). Making this judgment requires loading both Aggregates
// and comparing them side by side, so it can't be placed as a method on
// either Aggregate (doing so would require taking the entire other
// Aggregate as a parameter, breaking the boundary) — so it's kept as a
// separate Domain Service function instead. The caller
// (RequestRefundHandler) loads each from its own Repository, calls this
// function, and then calls refund.Approve(...) if approved or
// refund.Reject(...) if rejected.
//
// mlFraudRiskScore is a plain float64 already computed upstream by
// command.RefundFraudRiskScorer (a Technical Service) — this function never
// calls a model itself and never imports the Application-layer interface
// that produces the value; it only weighs the number against its own fixed
// threshold.
func EvaluateRefundEligibility(p *Payment, r *Refund, mlFraudRiskScore float64) RefundDecision {
	if p.Status != StatusCompleted {
		return RefundDecision{Approved: false, Reason: ErrRefundRequiresCompletedPayment.Error()}
	}
	if r.Amount > p.Amount {
		return RefundDecision{Approved: false, Reason: ErrRefundAmountExceedsPayment.Error()}
	}
	if mlFraudRiskScore >= mlFraudRiskRejectionThreshold {
		return RefundDecision{Approved: false, Reason: ErrRefundPatternFlaggedHighRisk.Error()}
	}
	return RefundDecision{Approved: true}
}
