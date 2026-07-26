import { Payment } from '@/payment/domain/payment'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { Refund } from '@/payment/domain/refund'
import { RefundEligibilityService } from '@/payment/domain/refund-eligibility-service'

// This judgment (whether the original payment is COMPLETED + whether the refund amount ≤ the
// payment amount + whether the ML-scored refund pattern is high risk) can't be verified by
// Payment alone or Refund alone — the fact that it's a rule verifiable only by loading both
// Aggregates together (plus the already-computed fraud-risk score) is why RefundEligibilityService
// is a Domain Service. This spec verifies that judgment directly (instantiated as a plain object
// with no NestJS dependency, and no model call — the score is always passed in as a plain value).
describe('RefundEligibilityService', () => {
  const service = new RefundEligibilityService()

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
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000), 0)

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_refund_amount_equals_the_payment_amount_then_approves', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(10000), 0)

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_payment_is_not_COMPLETED_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.PENDING, 10000), createRefund(5000), 0)

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('A refund can only be requested for a completed payment.')
  })

  it('evaluate_when_payment_is_CANCELLED_then_rejects', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.CANCELLED, 10000), createRefund(5000), 0)

    expect(decision.approved).toBe(false)
  })

  it('evaluate_when_refund_amount_exceeds_the_payment_amount_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(10001), 0)

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('The refund amount cannot exceed the payment amount.')
  })

  it('evaluate_when_ml_fraud_risk_score_is_at_or_above_the_threshold_then_rejects_and_returns_a_reason', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000), 0.8)

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.')
  })

  it('evaluate_when_ml_fraud_risk_score_is_below_the_threshold_then_still_approves', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000), 0.79)

    expect(decision.approved).toBe(true)
  })
})
