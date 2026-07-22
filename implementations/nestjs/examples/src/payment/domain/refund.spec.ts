import { Refund } from '@/payment/domain/refund'
import { RefundStatus } from '@/payment/payment-enum'
import { RefundApproved } from '@/payment/domain/refund-approved'

describe('Refund', () => {
  const createRefund = (status: RefundStatus = RefundStatus.REQUESTED): Refund => new Refund({
    refundId: 'refund-1',
    paymentId: 'payment-1',
    amount: 5000,
    reason: 'Defective product',
    status
  })

  it('create_when_valid_input_then_created_REQUESTED_with_no_events', () => {
    const refund = Refund.create({ paymentId: 'payment-1', amount: 5000, reason: 'Defective product' })

    expect(refund.status).toBe(RefundStatus.REQUESTED)
    expect(refund.amount).toBe(5000)
    expect(refund.reason).toBe('Defective product')
    expect(refund.domainEvents).toHaveLength(0)
  })

  describe('approve', () => {
    it('approve_when_REQUESTED_then_transitions_to_APPROVED_and_publishes_RefundApproved_event', () => {
      const refund = createRefund(RefundStatus.REQUESTED)

      refund.approve({ accountId: 'account-1', ownerId: 'owner-1' })

      expect(refund.status).toBe(RefundStatus.APPROVED)
      expect(refund.domainEvents).toHaveLength(1)
      const event = refund.domainEvents[0] as RefundApproved
      expect(event).toBeInstanceOf(RefundApproved)
      expect(event.accountId).toBe('account-1')
      expect(event.ownerId).toBe('owner-1')
      expect(event.amount).toBe(5000)
    })

    it('approve_when_not_REQUESTED_then_throws', () => {
      const refund = createRefund(RefundStatus.REJECTED)

      expect(() => refund.approve({ accountId: 'account-1', ownerId: 'owner-1' }))
        .toThrow('Can only be approved from the requested state.')
    })
  })

  describe('reject', () => {
    it('reject_when_REQUESTED_then_transitions_to_REJECTED_and_the_reason_is_kept_in_decisionNote', () => {
      const refund = createRefund(RefundStatus.REQUESTED)

      refund.reject('The refund amount exceeds the payment amount')

      expect(refund.status).toBe(RefundStatus.REJECTED)
      expect(refund.decisionNote).toBe('The refund amount exceeds the payment amount')
      expect(refund.domainEvents).toHaveLength(0)
    })

    it('reject_when_not_REQUESTED_then_throws', () => {
      const refund = createRefund(RefundStatus.APPROVED)

      expect(() => refund.reject('reason')).toThrow('Can only be rejected from the requested state.')
    })
  })

  describe('complete', () => {
    it('complete_when_APPROVED_then_transitions_to_COMPLETED', () => {
      const refund = createRefund(RefundStatus.APPROVED)

      refund.complete()

      expect(refund.status).toBe(RefundStatus.COMPLETED)
    })

    it('complete_when_not_APPROVED_then_throws', () => {
      const refund = createRefund(RefundStatus.REQUESTED)

      expect(() => refund.complete()).toThrow('Only an approved refund can be marked completed.')
    })
  })
})
