import { Test } from '@nestjs/testing'

import { RequestRefundCommandHandler } from '@/payment/application/command/request-refund-command-handler'
import { RequestRefundCommand } from '@/payment/application/command/request-refund-command'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { TransactionManager } from '@/database/transaction-manager'

// RefundEligibilityService (a Domain Service) is a plain class, so it isn't mocked — this
// spec verifies the flow where the Application layer loads both Repositories, delegates to
// the real judgment logic, and approves/rejects and saves the Refund based on the result.
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
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
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
