import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/account/application/service/notification-service'

@Injectable()
export class MoneyDepositedHandler {
  private readonly logger = new Logger(MoneyDepositedHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('MoneyDeposited')
  public async handle(event: {
    accountId: string
    email: string
    transactionId: string
    amount: { amount: number; currency: string }
    balanceAfter: { amount: number; currency: string }
  }): Promise<void> {
    this.logger.log({
      message: 'Deposit completed',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'MoneyDeposited',
      recipient: event.email,
      subject: '[Account] Your deposit is complete',
      body: `${event.amount.amount} ${event.amount.currency} has been deposited. Balance after deposit: ${event.balanceAfter.amount} ${event.balanceAfter.currency}`
    })
  }
}
