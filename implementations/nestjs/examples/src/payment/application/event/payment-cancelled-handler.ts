import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { PaymentCancelledIntegrationEventV1 } from '@/payment/application/integration-event/payment-cancelled-integration-event'

// Receives the internal Domain Event (PaymentCancelled), converts it into an external-BC
// Integration Event (payment.cancelled.v1), and writes it to the Outbox. Account BC subscribes
// to this and executes the compensating credit (deposit) — a compensating transaction that reverses an already-debited amount.
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
    this.logger.log({ message: 'Payment cancelled', payment_id: event.paymentId, account_id: event.accountId, reason: event.reason })

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
