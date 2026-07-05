import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/notification/notification-service'

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
      message: '출금 완료',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'MoneyWithdrawn',
      recipient: event.email,
      subject: '[Account] 출금이 완료되었습니다',
      body: `${event.amount.amount} ${event.amount.currency}이 출금되었습니다. 출금 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}`
    })
  }
}
