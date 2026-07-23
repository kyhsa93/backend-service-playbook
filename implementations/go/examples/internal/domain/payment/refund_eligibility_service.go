package payment

// RefundDecision is the judgment result of EvaluateRefundEligibility. Reason
// is used only as the rejection reason (left empty on approval).
type RefundDecision struct {
	Approved bool
	Reason   string
}

// fraudRiskRejectionThreshold — the fraud-risk score is produced upstream by
// command.RefundReasonClassifier (a Technical Service wrapping an LLM call)
// — this Domain Service never calls it and doesn't know an LLM produced it.
// It only receives the already-computed RefundReasonClassification as one
// more plain input alongside Payment/Refund, and applies its own fixed
// threshold. The LLM supplies a signal; this function still owns the actual
// approve/reject judgment.
const fraudRiskRejectionThreshold = 0.7

// mlFraudRiskRejectionThreshold — a second, independent signal, produced
// upstream by command.RefundFraudRiskScorer (a Technical Service trained on
// refund/payment history, see infrastructure/ml/refund_fraud_risk_scorer_native.go
// / refund_fraud_risk_scorer_http.go). Kept as its own plain float64 with its
// own threshold rather than merged into RefundReasonClassification, since
// it's computed from an entirely different input (structured history, not
// the free-text reason) and can fire independently of the LLM's
// category/score.
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
// classification is a plain value already computed upstream by
// command.RefundReasonClassifier (a Technical Service) — this function never
// calls an LLM itself and never imports the Application-layer interface that
// produces the value; it only reads the already-computed fields. Likewise,
// mlFraudRiskScore is a plain float64 already computed upstream by
// command.RefundFraudRiskScorer (also a Technical Service) — this function
// never calls a model itself either; it only weighs the number against its
// own threshold.
func EvaluateRefundEligibility(p *Payment, r *Refund, classification RefundReasonClassification, mlFraudRiskScore float64) RefundDecision {
	if p.Status != StatusCompleted {
		return RefundDecision{Approved: false, Reason: ErrRefundRequiresCompletedPayment.Error()}
	}
	if r.Amount > p.Amount {
		return RefundDecision{Approved: false, Reason: ErrRefundAmountExceedsPayment.Error()}
	}
	if classification.Category == RefundReasonFraudSuspected && classification.FraudRiskScore >= fraudRiskRejectionThreshold {
		return RefundDecision{Approved: false, Reason: ErrRefundFlaggedHighFraudRisk.Error()}
	}
	if mlFraudRiskScore >= mlFraudRiskRejectionThreshold {
		return RefundDecision{Approved: false, Reason: ErrRefundPatternFlaggedHighRisk.Error()}
	}
	return RefundDecision{Approved: true}
}
