import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/account/application/service/notification-service'

@Injectable()
export class MoneyWithdrawnHandler {
  private readonly logger = new Logger(MoneyWithdrawnHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('MoneyWithdrawn')
  public async handle(event: {
    accountId: string
    email: string
    transactionId: string
    amount: { amount: number; currency: string }
    balanceAfter: { amount: number; currency: string }
  }): Promise<void> {
    this.logger.log({
      message: 'Withdrawal completed',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'MoneyWithdrawn',
      recipient: event.email,
      subject: '[Account] Your withdrawal is complete',
      body: `${event.amount.amount} ${event.amount.currency} has been withdrawn. Balance after withdrawal: ${event.balanceAfter.amount} ${event.balanceAfter.currency}`
    })
  }
}
