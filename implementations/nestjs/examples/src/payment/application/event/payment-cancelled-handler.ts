import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { PaymentCancelledIntegrationEventV1 } from '@/payment/application/integration-event/payment-cancelled-integration-event'

// 내부 Domain Event(PaymentCancelled)를 수신해 외부 BC용 Integration Event
// (payment.cancelled.v1)로 변환해 Outbox에 적재한다. Account BC가 이를 구독해
// 보상 크레딧(deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
@Injectable()
export class PaymentCancelledHandler {
  private readonly logger = new Logger(PaymentCancelledHandler.name)

  constructor(private readonly outboxWriter: OutboxWriter) {}

  @HandleEvent('PaymentCancelled')
  public async handle(event: {
    paymentId: string
    accountId: string
    amount: number
    reason: string
    cancelledAt: string
  }): Promise<void> {
    this.logger.log({ message: '결제 취소됨', payment_id: event.paymentId, account_id: event.accountId, reason: event.reason })

    await this.outboxWriter.saveAll([
      new PaymentCancelledIntegrationEventV1(
        event.paymentId,
        event.accountId,
        event.amount,
        event.cancelledAt ?? new Date().toISOString()
      )
    ])
  }
}
