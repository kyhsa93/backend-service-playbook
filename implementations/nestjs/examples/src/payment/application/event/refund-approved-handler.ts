import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { RefundApprovedIntegrationEventV1 } from '@/payment/application/integration-event/refund-approved-integration-event'

// 내부 Domain Event(RefundApproved)를 수신해 외부 BC용 Integration Event
// (refund.approved.v1)로 변환해 Outbox에 적재한다. Account BC가 이를 구독해
// 환불 크레딧(deposit)을 실행한다.
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
    this.logger.log({ message: '환불 승인됨', refund_id: event.refundId, payment_id: event.paymentId, account_id: event.accountId })

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
