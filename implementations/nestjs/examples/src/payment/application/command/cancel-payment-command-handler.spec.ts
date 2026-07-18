import { Test } from '@nestjs/testing'

import { CancelPaymentCommandHandler } from '@/payment/application/command/cancel-payment-command-handler'
import { CancelPaymentCommand } from '@/payment/application/command/cancel-payment-command'
import { OutboxRelay } from '@/payment/application/event/outbox-relay'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { TransactionManager } from '@/database/transaction-manager'

describe('CancelPaymentCommandHandler', () => {
  let handler: CancelPaymentCommandHandler
  let paymentRepository: jest.Mocked<PaymentRepository>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        CancelPaymentCommandHandler,
        { provide: PaymentRepository, useValue: { findPayments: jest.fn(), savePayment: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } },
        { provide: OutboxRelay, useValue: { processPending: jest.fn() } }
      ]
    }).compile()

    handler = module.get(CancelPaymentCommandHandler)
    paymentRepository = module.get(PaymentRepository)
  })

  const createPayment = (status: PaymentStatus): Payment => new Payment({
    paymentId: 'payment-1', cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1', amount: 5000, status
  })

  it('execute_when_완료된_결제_then_취소하고_저장한다', async () => {
    const payment = createPayment(PaymentStatus.COMPLETED)
    paymentRepository.findPayments.mockResolvedValue({ payments: [payment], count: 1 })

    const result = await handler.execute(new CancelPaymentCommand({ paymentId: 'payment-1', reason: '고객 요청', requesterId: 'owner-1' }))

    expect(result.status).toBe(PaymentStatus.CANCELLED)
    expect(paymentRepository.savePayment).toHaveBeenCalledWith(payment)
  })

  it('execute_when_결제가_없으면_then_에러를_throw한다', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [], count: 0 })

    await expect(handler.execute(new CancelPaymentCommand({ paymentId: 'missing', reason: '사유', requesterId: 'owner-1' })))
      .rejects.toThrow(PaymentErrorMessage['결제를 찾을 수 없습니다.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_완료_상태가_아니면_then_에러를_throw한다', async () => {
    const payment = createPayment(PaymentStatus.PENDING)
    paymentRepository.findPayments.mockResolvedValue({ payments: [payment], count: 1 })

    await expect(handler.execute(new CancelPaymentCommand({ paymentId: 'payment-1', reason: '사유', requesterId: 'owner-1' })))
      .rejects.toThrow(PaymentErrorMessage['완료된 결제만 취소할 수 있습니다.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })
})
