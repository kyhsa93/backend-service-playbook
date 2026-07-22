import { Test } from '@nestjs/testing'

import { CancelPaymentCommandHandler } from '@/payment/application/command/cancel-payment-command-handler'
import { CancelPaymentCommand } from '@/payment/application/command/cancel-payment-command'
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
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(CancelPaymentCommandHandler)
    paymentRepository = module.get(PaymentRepository)
  })

  const createPayment = (status: PaymentStatus): Payment => new Payment({
    paymentId: 'payment-1', cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1', amount: 5000, status
  })

  it('execute_when_payment_is_completed_then_cancels_and_saves', async () => {
    const payment = createPayment(PaymentStatus.COMPLETED)
    paymentRepository.findPayments.mockResolvedValue({ payments: [payment], count: 1 })

    const result = await handler.execute(new CancelPaymentCommand({ paymentId: 'payment-1', reason: 'Customer request', requesterId: 'owner-1' }))

    expect(result.status).toBe(PaymentStatus.CANCELLED)
    expect(paymentRepository.savePayment).toHaveBeenCalledWith(payment)
  })

  it('execute_when_the_payment_does_not_exist_then_throws', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [], count: 0 })

    await expect(handler.execute(new CancelPaymentCommand({ paymentId: 'missing', reason: 'reason', requesterId: 'owner-1' })))
      .rejects.toThrow(PaymentErrorMessage['Payment not found.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_not_in_the_completed_state_then_throws', async () => {
    const payment = createPayment(PaymentStatus.PENDING)
    paymentRepository.findPayments.mockResolvedValue({ payments: [payment], count: 1 })

    await expect(handler.execute(new CancelPaymentCommand({ paymentId: 'payment-1', reason: 'reason', requesterId: 'owner-1' })))
      .rejects.toThrow(PaymentErrorMessage['Only a completed payment can be cancelled.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })
})
