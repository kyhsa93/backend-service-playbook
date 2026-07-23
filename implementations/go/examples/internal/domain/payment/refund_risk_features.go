package payment

// RefundRiskFeatures is the feature vector command.RefundFraudRiskScorer
// scores. Assembled by the Application layer (RequestRefundHandler) from the
// requester's refund history (RefundQuery.SummarizeRefundsByOwner) plus the
// current Payment/Refund pair — never by the Domain Service or the scorer
// itself, so both stay decoupled from how history is stored/queried.
type RefundRiskFeatures struct {
	RefundCountLast30Days         int
	RejectedRefundCountLast30Days int
	RefundToPaymentAmountRatio    float64
	MinutesSincePayment           float64
}
