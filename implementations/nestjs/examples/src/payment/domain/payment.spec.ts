import { Payment } from '@/payment/domain/payment'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentCancelled } from '@/payment/domain/payment-cancelled'
import { PaymentCompleted } from '@/payment/domain/payment-completed'

describe('Payment', () => {
  const createPayment = (status: PaymentStatus = PaymentStatus.PENDING): Payment => new Payment({
    paymentId: 'payment-1',
    cardId: 'card-1',
    accountId: 'account-1',
    ownerId: 'owner-1',
    amount: 10000,
    status
  })

  it('create_when_정상_입력_then_PENDING_상태로_생성되고_이벤트가_없다', () => {
    const payment = Payment.create({ cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1', amount: 10000 })

    expect(payment.status).toBe(PaymentStatus.PENDING)
    expect(payment.amount).toBe(10000)
    expect(payment.paymentId).toEqual(expect.any(String))
    expect(payment.domainEvents).toHaveLength(0)
  })

  describe('complete', () => {
    it('complete_when_PENDING_상태_then_COMPLETED로_전환하고_PaymentCompleted_이벤트가_발행된다', () => {
      const payment = createPayment(PaymentStatus.PENDING)

      payment.complete()

      expect(payment.status).toBe(PaymentStatus.COMPLETED)
      expect(payment.domainEvents).toHaveLength(1)
      expect(payment.domainEvents[0]).toBeInstanceOf(PaymentCompleted)
    })

    it('complete_when_PENDING이_아니면_then_에러를_throw한다', () => {
      const payment = createPayment(PaymentStatus.COMPLETED)

      expect(() => payment.complete()).toThrow('결제 대기 상태에서만 완료 처리할 수 있습니다.')
    })
  })

  describe('fail', () => {
    it('fail_when_PENDING_상태_then_FAILED로_전환한다', () => {
      const payment = createPayment(PaymentStatus.PENDING)

      payment.fail('카드 승인 거부')

      expect(payment.status).toBe(PaymentStatus.FAILED)
    })

    it('fail_when_PENDING이_아니면_then_에러를_throw한다', () => {
      const payment = createPayment(PaymentStatus.COMPLETED)

      expect(() => payment.fail('사유')).toThrow('결제 대기 상태에서만 실패 처리할 수 있습니다.')
    })
  })

  describe('cancel', () => {
    it('cancel_when_COMPLETED_상태_then_CANCELLED로_전환하고_PaymentCancelled_이벤트가_발행된다', () => {
      const payment = createPayment(PaymentStatus.COMPLETED)

      payment.cancel('고객 요청')

      expect(payment.status).toBe(PaymentStatus.CANCELLED)
      expect(payment.domainEvents).toHaveLength(1)
      expect(payment.domainEvents[0]).toBeInstanceOf(PaymentCancelled)
      expect((payment.domainEvents[0] as PaymentCancelled).reason).toBe('고객 요청')
    })

    it('cancel_when_PENDING_상태면_then_에러를_throw한다', () => {
      const payment = createPayment(PaymentStatus.PENDING)

      expect(() => payment.cancel('사유')).toThrow('완료된 결제만 취소할 수 있습니다.')
    })

    it('cancel_when_이미_취소된_결제면_then_에러를_throw한다', () => {
      const payment = createPayment(PaymentStatus.CANCELLED)

      expect(() => payment.cancel('사유')).toThrow('완료된 결제만 취소할 수 있습니다.')
    })
  })

  describe('clearEvents', () => {
    it('clearEvents_when_호출_then_domainEvents가_비워진다', () => {
      const payment = createPayment(PaymentStatus.PENDING)
      payment.complete()

      payment.clearEvents()

      expect(payment.domainEvents).toHaveLength(0)
    })
  })
})
