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

  it('create_when_valid_input_then_created_PENDING_with_no_events', () => {
    const payment = Payment.create({ cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1', amount: 10000 })

    expect(payment.status).toBe(PaymentStatus.PENDING)
    expect(payment.amount).toBe(10000)
    expect(payment.paymentId).toEqual(expect.any(String))
    expect(payment.domainEvents).toHaveLength(0)
  })

  describe('complete', () => {
    it('complete_when_PENDING_then_transitions_to_COMPLETED_and_publishes_PaymentCompleted_event', () => {
      const payment = createPayment(PaymentStatus.PENDING)

      payment.complete()

      expect(payment.status).toBe(PaymentStatus.COMPLETED)
      expect(payment.domainEvents).toHaveLength(1)
      expect(payment.domainEvents[0]).toBeInstanceOf(PaymentCompleted)
    })

    it('complete_when_not_PENDING_then_throws', () => {
      const payment = createPayment(PaymentStatus.COMPLETED)

      expect(() => payment.complete()).toThrow('Can only be marked completed from the pending state.')
    })
  })

  describe('fail', () => {
    it('fail_when_PENDING_then_transitions_to_FAILED', () => {
      const payment = createPayment(PaymentStatus.PENDING)

      payment.fail('Card authorization declined')

      expect(payment.status).toBe(PaymentStatus.FAILED)
    })

    it('fail_when_not_PENDING_then_throws', () => {
      const payment = createPayment(PaymentStatus.COMPLETED)

      expect(() => payment.fail('reason')).toThrow('Can only be marked failed from the pending state.')
    })
  })

  describe('cancel', () => {
    it('cancel_when_COMPLETED_then_transitions_to_CANCELLED_and_publishes_PaymentCancelled_event', () => {
      const payment = createPayment(PaymentStatus.COMPLETED)

      payment.cancel('Customer request')

      expect(payment.status).toBe(PaymentStatus.CANCELLED)
      expect(payment.domainEvents).toHaveLength(1)
      expect(payment.domainEvents[0]).toBeInstanceOf(PaymentCancelled)
      expect((payment.domainEvents[0] as PaymentCancelled).reason).toBe('Customer request')
    })

    it('cancel_when_PENDING_then_throws', () => {
      const payment = createPayment(PaymentStatus.PENDING)

      expect(() => payment.cancel('reason')).toThrow('Only a completed payment can be cancelled.')
    })

    it('cancel_when_payment_is_already_cancelled_then_throws', () => {
      const payment = createPayment(PaymentStatus.CANCELLED)

      expect(() => payment.cancel('reason')).toThrow('Only a completed payment can be cancelled.')
    })
  })

  describe('clearEvents', () => {
    it('clearEvents_when_called_then_domainEvents_is_emptied', () => {
      const payment = createPayment(PaymentStatus.PENDING)
      payment.complete()

      payment.clearEvents()

      expect(payment.domainEvents).toHaveLength(0)
    })
  })
})
