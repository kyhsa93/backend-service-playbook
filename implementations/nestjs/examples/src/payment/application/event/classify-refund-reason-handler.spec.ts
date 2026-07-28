import { Test } from '@nestjs/testing'

import { TransactionManager } from '@/database/transaction-manager'
import { ClassifyRefundReasonHandler } from '@/payment/application/event/classify-refund-reason-handler'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { Refund } from '@/payment/domain/refund'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { RefundStatus } from '@/payment/payment-enum'

describe('ClassifyRefundReasonHandler', () => {
  let handler: ClassifyRefundReasonHandler
  let refundReasonClassifier: jest.Mocked<RefundReasonClassifier>
  let refundRepository: jest.Mocked<RefundRepository>

  const refund = new Refund({
    refundId: 'refund-1', paymentId: 'payment-1', amount: 5000,
    reason: 'The item arrived broken', status: RefundStatus.APPROVED
  })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        ClassifyRefundReasonHandler,
        { provide: RefundReasonClassifier, useValue: { classify: jest.fn() } },
        { provide: RefundRepository, useValue: { findRefunds: jest.fn(), saveRefund: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(ClassifyRefundReasonHandler)
    refundReasonClassifier = module.get(RefundReasonClassifier)
    refundRepository = module.get(RefundRepository)
  })

  it('handle_when_the_refund_still_exists_then_classifies_and_saves_the_category', async () => {
    refundRepository.findRefunds.mockResolvedValue({ refunds: [refund], count: 1 })
    refundReasonClassifier.classify.mockResolvedValue('DEFECTIVE_PRODUCT')

    await handler.handle({ refundId: 'refund-1', reason: 'The item arrived broken' })

    expect(refundReasonClassifier.classify).toHaveBeenCalledWith('The item arrived broken')
    expect(refundRepository.findRefunds).toHaveBeenCalledWith({ refundId: 'refund-1', take: 1, page: 0 })
    expect(refundRepository.saveRefund).toHaveBeenCalledWith(
      expect.objectContaining({ refundId: 'refund-1', reasonCategory: 'DEFECTIVE_PRODUCT' })
    )
  })

  it('handle_when_the_refund_no_longer_exists_then_skips_classification_without_throwing', async () => {
    refundRepository.findRefunds.mockResolvedValue({ refunds: [], count: 0 })

    await handler.handle({ refundId: 'refund-1', reason: 'The item arrived broken' })

    expect(refundReasonClassifier.classify).not.toHaveBeenCalled()
    expect(refundRepository.saveRefund).not.toHaveBeenCalled()
  })
})
