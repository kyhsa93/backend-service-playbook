import { Test } from '@nestjs/testing'

import { RequestRefundCommandHandler } from '@/payment/application/command/request-refund-command-handler'
import { RequestRefundCommand } from '@/payment/application/command/request-refund-command'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentStatus, RefundStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { TransactionManager } from '@/database/transaction-manager'

// RefundEligibilityService (a Domain Service) is a plain class, so it isn't mocked — this
// spec verifies the flow where the Application layer loads both Repositories, classifies the
// reason via the (mocked) RefundReasonClassifier Technical Service, delegates to the real
// judgment logic, and approves/rejects and saves the Refund based on the result. Mocking the
// Technical Service interface — rather than hitting the real LLM — is exactly the benefit
// described in domain-service.md: no external dependency, no non-determinism, in this test.
describe('RequestRefundCommandHandler', () => {
  let handler: RequestRefundCommandHandler
  let paymentRepository: jest.Mocked<PaymentRepository>
  let refundRepository: jest.Mocked<RefundRepository>
  let refundReasonClassifier: jest.Mocked<RefundReasonClassifier>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        RequestRefundCommandHandler,
        { provide: PaymentRepository, useValue: { findPayments: jest.fn(), savePayment: jest.fn() } },
        { provide: RefundRepository, useValue: { findRefunds: jest.fn(), saveRefund: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } },
        { provide: RefundReasonClassifier, useValue: { classify: jest.fn() } }
      ]
    }).compile()

    handler = module.get(RequestRefundCommandHandler)
    paymentRepository = module.get(PaymentRepository)
    refundRepository = module.get(RefundRepository)
    refundReasonClassifier = module.get(RefundReasonClassifier)
    refundReasonClassifier.classify.mockResolvedValue({ category: 'defective_product', fraudRiskScore: 0.1 })
  })

  const createPayment = (status: PaymentStatus, amount = 10000): Payment => new Payment({
    paymentId: 'payment-1', cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1', amount, status
  })

  it('execute_when_payment_is_completed_and_refund_amount_is_within_the_payment_amount_then_approves_and_saves', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.COMPLETED, 10000)], count: 1 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 5000, reason: 'Defective product', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.APPROVED)
    expect(refundRepository.saveRefund).toHaveBeenCalledWith(refund)
    expect(refundReasonClassifier.classify).toHaveBeenCalledWith('Defective product')
  })

  it('execute_when_payment_is_not_COMPLETED_then_rejects_and_saves', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.PENDING, 10000)], count: 1 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 5000, reason: 'Defective product', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.REJECTED)
    expect(refund.decisionNote).toBe(PaymentErrorMessage['A refund can only be requested for a completed payment.'])
    expect(refundRepository.saveRefund).toHaveBeenCalledWith(refund)
  })

  it('execute_when_the_refund_amount_exceeds_the_payment_amount_then_rejects_and_saves', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.COMPLETED, 10000)], count: 1 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 20000, reason: 'Defective product', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.REJECTED)
    expect(refund.decisionNote).toBe(PaymentErrorMessage['The refund amount cannot exceed the payment amount.'])
  })

  it('execute_when_the_classifier_flags_high_fraud_risk_then_rejects_and_saves', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [createPayment(PaymentStatus.COMPLETED, 10000)], count: 1 })
    refundReasonClassifier.classify.mockResolvedValue({ category: 'fraud_suspected', fraudRiskScore: 0.95 })

    const refund = await handler.execute(
      new RequestRefundCommand({ paymentId: 'payment-1', amount: 5000, reason: 'suspicious reason', requesterId: 'owner-1' })
    )

    expect(refund.status).toBe(RefundStatus.REJECTED)
    expect(refund.decisionNote).toBe(PaymentErrorMessage['This refund reason was flagged as high fraud risk and requires manual review.'])
    expect(refundRepository.saveRefund).toHaveBeenCalledWith(refund)
  })

  it('execute_when_the_payment_does_not_exist_then_throws', async () => {
    paymentRepository.findPayments.mockResolvedValue({ payments: [], count: 0 })

    await expect(handler.execute(
      new RequestRefundCommand({ paymentId: 'missing', amount: 5000, reason: 'reason', requesterId: 'owner-1' })
    )).rejects.toThrow(PaymentErrorMessage['Payment not found.'])
    expect(refundRepository.saveRefund).not.toHaveBeenCalled()
  })
})
