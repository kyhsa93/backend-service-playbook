import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { PaymentCompletedIntegrationEventV1 } from '@/payment/application/integration-event/payment-completed-integration-event'

// An Application EventHandler that receives the internal Domain Event (PaymentCompleted),
// converts it into an external-BC Integration Event (payment.completed.v1), and writes it to
// the Outbox. Account BC subscribes to this Integration Event and performs the actual debit (withdraw).
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
