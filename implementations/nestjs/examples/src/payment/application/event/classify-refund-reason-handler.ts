import { Injectable, Logger } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { HandleEvent } from '@/outbox/event-handler-registry'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { RefundRepository } from '@/payment/domain/refund-repository'

// Reacts to RefundRequested (published unconditionally by Refund.create(), before
// RefundEligibilityService's approve/reject judgment even runs) to classify the refund's
// free-text reason for ops-analytics reporting only — see refund-reason-insights-query.ts.
// Runs off the request hot path (RequestRefundCommandHandler never calls an LLM directly),
// and its result is never read back into any eligibility/approval decision. Inherently
// idempotent: a retried delivery just re-runs the same find→categorize→save.
@Injectable()
export class ClassifyRefundReasonHandler {
  private readonly logger = new Logger(ClassifyRefundReasonHandler.name)

  constructor(
    private readonly refundReasonClassifier: RefundReasonClassifier,
    private readonly refundRepository: RefundRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  @HandleEvent('RefundRequested')
  public async handle(event: { refundId: string; reason: string }): Promise<void> {
    const refund = await this.refundRepository
      .findRefunds({ refundId: event.refundId, take: 1, page: 0 })
      .then((r) => r.refunds.pop())
    if (!refund) return

    const category = await this.refundReasonClassifier.classify(event.reason)
    refund.categorizeReason(category)

    await this.transactionManager.run(async () => {
      await this.refundRepository.saveRefund(refund)
    })
    this.logger.log({ message: 'Refund reason classified', refund_id: event.refundId, category })
  }
}
