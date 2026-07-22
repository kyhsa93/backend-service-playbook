package payment

// RefundReasonCategory is the fixed set of buckets RefundReasonClassifier (a
// Technical Service, see command.RefundReasonClassifier) sorts a refund's
// free-text reason into.
type RefundReasonCategory string

const (
	RefundReasonDefectiveProduct RefundReasonCategory = "defective_product"
	RefundReasonNotAsDescribed   RefundReasonCategory = "not_as_described"
	RefundReasonDuplicateCharge  RefundReasonCategory = "duplicate_charge"
	RefundReasonChangedMind      RefundReasonCategory = "changed_mind"
	RefundReasonFraudSuspected   RefundReasonCategory = "fraud_suspected"
	RefundReasonOther            RefundReasonCategory = "other"
)

// RefundReasonClassification is the shape of a refund reason's classification
// result — a plain Domain-layer value, not tied to how it was produced.
// EvaluateRefundEligibility (refund_eligibility_service.go) reads this as one
// more input alongside Payment/Refund; this package never imports the
// Application-layer command.RefundReasonClassifier interface that produces
// it (that would violate domain-layer isolation — the Domain layer must not
// depend on any other layer). The Technical Service interface
// (application/command/refund_reason_classifier.go) imports this type from
// here instead.
type RefundReasonClassification struct {
	Category       RefundReasonCategory
	FraudRiskScore float64
}
