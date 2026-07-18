import { Refund } from '@/payment/domain/refund'
import { RefundStatus } from '@/payment/payment-enum'
import { RefundApproved } from '@/payment/domain/refund-approved'

describe('Refund', () => {
  const createRefund = (status: RefundStatus = RefundStatus.REQUESTED): Refund => new Refund({
    refundId: 'refund-1',
    paymentId: 'payment-1',
    amount: 5000,
    reason: '상품 불량',
    status
  })

  it('create_when_정상_입력_then_REQUESTED_상태로_생성되고_이벤트가_없다', () => {
    const refund = Refund.create({ paymentId: 'payment-1', amount: 5000, reason: '상품 불량' })

    expect(refund.status).toBe(RefundStatus.REQUESTED)
    expect(refund.amount).toBe(5000)
    expect(refund.reason).toBe('상품 불량')
    expect(refund.domainEvents).toHaveLength(0)
  })

  describe('approve', () => {
    it('approve_when_REQUESTED_상태_then_APPROVED로_전환하고_RefundApproved_이벤트가_발행된다', () => {
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

    it('approve_when_REQUESTED가_아니면_then_에러를_throw한다', () => {
      const refund = createRefund(RefundStatus.REJECTED)

      expect(() => refund.approve({ accountId: 'account-1', ownerId: 'owner-1' }))
        .toThrow('환불 요청 상태에서만 승인할 수 있습니다.')
    })
  })

  describe('reject', () => {
    it('reject_when_REQUESTED_상태_then_REJECTED로_전환하고_decisionNote에_이유가_남는다', () => {
      const refund = createRefund(RefundStatus.REQUESTED)

      refund.reject('환불 금액이 결제 금액을 초과합니다')

      expect(refund.status).toBe(RefundStatus.REJECTED)
      expect(refund.decisionNote).toBe('환불 금액이 결제 금액을 초과합니다')
      expect(refund.domainEvents).toHaveLength(0)
    })

    it('reject_when_REQUESTED가_아니면_then_에러를_throw한다', () => {
      const refund = createRefund(RefundStatus.APPROVED)

      expect(() => refund.reject('사유')).toThrow('환불 요청 상태에서만 거부할 수 있습니다.')
    })
  })

  describe('complete', () => {
    it('complete_when_APPROVED_상태_then_COMPLETED로_전환한다', () => {
      const refund = createRefund(RefundStatus.APPROVED)

      refund.complete()

      expect(refund.status).toBe(RefundStatus.COMPLETED)
    })

    it('complete_when_APPROVED가_아니면_then_에러를_throw한다', () => {
      const refund = createRefund(RefundStatus.REQUESTED)

      expect(() => refund.complete()).toThrow('승인된 환불만 완료 처리할 수 있습니다.')
    })
  })
})
