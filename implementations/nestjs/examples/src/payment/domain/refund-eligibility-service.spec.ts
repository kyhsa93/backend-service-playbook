import { Payment } from '@/payment/domain/payment'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { Refund } from '@/payment/domain/refund'
import { RefundEligibilityService } from '@/payment/domain/refund-eligibility-service'

// 이 판단(원 결제 COMPLETED 여부 + 환불 금액 ≤ 결제 금액)은 Payment 혼자, Refund
// 혼자로는 검증할 수 없다 — 두 Aggregate를 함께 로드해야만 검증 가능한 규칙이라는
// 것이 RefundEligibilityService가 Domain Service인 이유다. 이 스펙은 그 판단 자체를
// 직접 검증한다(NestJS 의존성 없이 순수 객체로 인스턴스화한다).
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
