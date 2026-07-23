import { Payment } from '@/payment/domain/payment'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { Refund } from '@/payment/domain/refund'
import { RefundEligibilityService } from '@/payment/domain/refund-eligibility-service'
import { RefundReasonClassification } from '@/payment/domain/refund-reason-classification'

// This judgment (whether the original payment is COMPLETED + whether the refund amount ≤ the
// payment amount + whether the LLM-classified reason is high fraud risk) can't be verified by
// Payment alone or Refund alone — the fact that it's a rule verifiable only by loading both
// Aggregates together (plus the already-computed classification) is why RefundEligibilityService
// is a Domain Service. This spec verifies that judgment directly (instantiated as a plain object
// with no NestJS dependency, and no LLM call — classification is always passed in as a plain value).
describe('RefundEligibilityService', () => {
  const service = new RefundEligibilityService()

  const NOT_FRAUD: RefundReasonClassification = { category: 'defective_product', fraudRiskScore: 0.1 }

  const createPayment = (status: PaymentStatus, amount = 10000): Payment => new Payment({
    paymentId: 'payment-1',
    cardId: 'card-1',
    accountId: 'account-1',
    ownerId: 'owner-1',
    amount,
    status
  })

  const createRefund = (amount: number): Refund => new Refund({
    refundId: 'refund-1',
    paymentId: 'payment-1',
    amount,
    reason: 'Defective product',
    status: RefundStatus.REQUESTED
  })

  it('evaluate_when_payment_is_COMPLETED_and_refund_amount_is_within_the_payment_amount_then_approves', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000), NOT_FRAUD, 0)

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_refund_amount_equals_the_payment_amount_then_approves', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(10000), NOT_FRAUD, 0)

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_payment_is_not_COMPLETED_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.PENDING, 10000), createRefund(5000), NOT_FRAUD, 0)

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('A refund can only be requested for a completed payment.')
  })

  it('evaluate_when_payment_is_CANCELLED_then_rejects', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.CANCELLED, 10000), createRefund(5000), NOT_FRAUD, 0)

    expect(decision.approved).toBe(false)
  })

  it('evaluate_when_refund_amount_exceeds_the_payment_amount_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(10001), NOT_FRAUD, 0)

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('The refund amount cannot exceed the payment amount.')
  })

  it('evaluate_when_classification_is_fraud_suspected_with_a_high_score_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(
      createPayment(PaymentStatus.COMPLETED, 10000),
      createRefund(5000),
      { category: 'fraud_suspected', fraudRiskScore: 0.9 },
      0
    )

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('This refund reason was flagged as high fraud risk and requires manual review.')
  })

  it('evaluate_when_classification_is_fraud_suspected_but_the_score_is_below_the_threshold_then_still_approves', () => {
    const decision = service.evaluate(
      createPayment(PaymentStatus.COMPLETED, 10000),
      createRefund(5000),
      { category: 'fraud_suspected', fraudRiskScore: 0.5 },
      0
    )

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_the_score_is_high_but_the_category_is_not_fraud_suspected_then_still_approves', () => {
    const decision = service.evaluate(
      createPayment(PaymentStatus.COMPLETED, 10000),
      createRefund(5000),
      { category: 'other', fraudRiskScore: 0.95 },
      0
    )

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_ml_fraud_risk_score_is_at_or_above_the_threshold_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000), NOT_FRAUD, 0.8)

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.')
  })

  it('evaluate_when_ml_fraud_risk_score_is_below_the_threshold_then_still_approves', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000), NOT_FRAUD, 0.79)

    expect(decision.approved).toBe(true)
  })
})
