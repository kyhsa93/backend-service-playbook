import { Payment } from '@/payment/domain/payment'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { Refund } from '@/payment/domain/refund'
import { RefundEligibilityService } from '@/payment/domain/refund-eligibility-service'

// This judgment (whether the original payment is COMPLETED + whether the refund amount ≤ the
// payment amount) can't be verified by Payment alone or Refund alone — the fact that it's a
// rule verifiable only by loading both Aggregates together is why RefundEligibilityService is
// a Domain Service. This spec verifies that judgment directly (instantiated as a plain object with no NestJS dependency).
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
    reason: '상품 불량',
    status: RefundStatus.REQUESTED
  })

  it('evaluate_when_결제가_COMPLETED이고_환불금액이_결제금액_이하_then_승인한다', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(5000))

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_환불금액이_결제금액과_같으면_then_승인한다', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(10000))

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_결제가_COMPLETED가_아니면_then_거부하고_이유를_반환한다', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.PENDING, 10000), createRefund(5000))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('완료된 결제에 대해서만 환불을 요청할 수 있습니다.')
  })

  it('evaluate_when_결제가_CANCELLED면_then_거부한다', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.CANCELLED, 10000), createRefund(5000))

    expect(decision.approved).toBe(false)
  })

  it('evaluate_when_환불금액이_결제금액을_초과하면_then_거부하고_이유를_반환한다', () => {
    const decision = service.evaluate(createPayment(PaymentStatus.COMPLETED, 10000), createRefund(10001))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('환불 금액은 결제 금액을 초과할 수 없습니다.')
  })
})
