import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/notification/notification-service'

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
      message: '입금 완료',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'MoneyDeposited',
      recipient: event.email,
      subject: '[Account] 입금이 완료되었습니다',
      body: `${event.amount.amount} ${event.amount.currency}이 입금되었습니다. 입금 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}`
    })
  }
}
