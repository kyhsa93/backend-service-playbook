import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { PaymentCompletedIntegrationEventV1 } from '@/payment/application/integration-event/payment-completed-integration-event'

// 내부 Domain Event(PaymentCompleted)를 수신해 외부 BC용 Integration Event
// (payment.completed.v1)로 변환해 Outbox에 적재하는 Application EventHandler.
// Account BC가 이 Integration Event를 구독해 실제 차감(withdraw)을 수행한다.
@Injectable()
export class PaymentCompletedHandler {
  private readonly logger = new Logger(PaymentCompletedHandler.name)

  constructor(private readonly outboxWriter: OutboxWriter) {}

  @HandleEvent('PaymentCompleted')
  public async handle(event: {
    paymentId: string
    accountId: string
    amount: number
    completedAt: string
  }): Promise<void> {
    this.logger.log({ message: '결제 완료됨', payment_id: event.paymentId, account_id: event.accountId, amount: event.amount })

    await this.outboxWriter.saveAll([
      new PaymentCompletedIntegrationEventV1(
        event.paymentId,
        event.accountId,
        event.amount,
        event.completedAt ?? new Date().toISOString()
      )
    ])
  }
}
