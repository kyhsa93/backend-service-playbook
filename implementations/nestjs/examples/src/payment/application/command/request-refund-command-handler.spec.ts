import { Test } from '@nestjs/testing'

import { RequestRefundCommandHandler } from '@/payment/application/command/request-refund-command-handler'
import { RequestRefundCommand } from '@/payment/application/command/request-refund-command'
import { OutboxRelay } from '@/payment/application/event/outbox-relay'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { TransactionManager } from '@/database/transaction-manager'

// RefundEligibilityService(Domain Service)는 순수 클래스라 목(mock)하지 않는다 —
// 이 스펙은 Application 레이어가 두 Repository를 로드해 실제 판단 로직에 위임하고,
// 그 결과에 따라 Refund를 승인/거부해 저장하는 흐름을 검증한다.
describe('RequestRefundCommandHandler', () => {
  let handler: RequestRefundCommandHandler
  let paymentRepository: jest.Mocked<PaymentRepository>
  let refundRepository: jest.Mocked<RefundRepository>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        RequestRefundCommandHandler,
        { provide: PaymentRepository, useValue: { findPayments: jest.fn(), savePayment: jest.fn() } },
        { provide: RefundRepository, useValue: { findRefunds: jest.fn(), saveRefund: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } },
        { provide: OutboxRelay, useValue: { processPending: jest.fn() } }
      ]
    }).compile()

    handler = module.get(RequestRefundCommandHandler)
    paymentRepository = module.get(PaymentRepository)
    refundRepository = module.get(RefundRepository)
  })

  const createPayment = (status: PaymentStatus, amount = 10000): Payment => new Payment({
    paymentId: 'payment-1', cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1', amount, status
  })

  it('execute_when_완료된_결제이고_환불금액이_결제금액_이하면_then_승인하고_저장한다', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.COMPLETED, 10000)], count: 1 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 5000, reason: '상품 불량', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.APPROVED)
    expect(refundRepository.saveRefund).toHaveBeenCalledWith(refund)
  })

  it('execute_when_결제가_COMPLETED가_아니면_then_거부하고_저장한다', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.PENDING, 10000)], count: 1 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 5000, reason: '상품 불량', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.REJECTED)
    expect(refund.decisionNote).toBe(PaymentErrorMessage['완료된 결제에 대해서만 환불을 요청할 수 있습니다.'])
    expect(refundRepository.saveRefund).toHaveBeenCalledWith(refund)
  })

  it('execute_when_환불금액이_결제금액을_초과하면_then_거부하고_저장한다', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.COMPLETED, 10000)], count: 1 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 20000, reason: '상품 불량', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.REJECTED)
    expect(refund.decisionNote).toBe(PaymentErrorMessage['환불 금액은 결제 금액을 초과할 수 없습니다.'])
  })

  it('execute_when_결제가_없으면_then_에러를_throw한다', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [], count: 0 })

    await expect(handler.execute(
      new RequestRefundCommand({ paymentId: 'missing', amount: 5000, reason: '사유', requesterId: 'owner-1' })
    )).rejects.toThrow(PaymentErrorMessage['결제를 찾을 수 없습니다.'])
    expect(refundRepository.saveRefund).not.toHaveBeenCalled()
  })
})
