import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { RefundApprovedIntegrationEventV1 } from '@/payment/application/integration-event/refund-approved-integration-event'

// Receives the internal Domain Event (RefundApproved), converts it into an external-BC
// Integration Event (refund.approved.v1), and writes it to the Outbox. Account BC subscribes
// to this and executes the refund credit (deposit).
@Injectable()
export class RefundApprovedHandler {
  private readonly logger = new Logger(RefundApprovedHandler.name)

  constructor(private readonly outboxWriter: OutboxWriter) {}

  @HandleEvent('RefundApproved')
  public async handle(event: {
    refundId: string
    paymentId: string
    accountId: string
    amount: number
    approvedAt: string
  }): Promise<void> {
    this.logger.log({ message: 'Refund approved', refund_id: event.refundId, payment_id: event.paymentId, account_id: event.accountId })

    await this.outboxWriter.saveAll([
      new RefundApprovedIntegrationEventV1(
        event.refundId,
        event.paymentId,
        event.accountId,
        event.amount,
        event.approvedAt ?? new Date().toISOString()
      )
    ])
  }
}
