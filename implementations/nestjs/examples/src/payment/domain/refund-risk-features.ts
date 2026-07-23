// The feature vector RefundFraudRiskScorer scores. Assembled by the Application layer from
// the requester's refund history (RefundRepository.summarizeRefundsByOwner) plus the current
// Payment/Refund pair — never by the Domain Service or the scorer itself, so both stay
// decoupled from how history is stored/queried.
export interface RefundRiskFeatures {
  readonly refundCountLast30Days: number
  readonly rejectedRefundCountLast30Days: number
  readonly refundToPaymentAmountRatio: number
  readonly minutesSincePayment: number
}
