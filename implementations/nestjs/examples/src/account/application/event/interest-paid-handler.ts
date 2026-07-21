import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/account/application/service/notification-service'

@Injectable()
export class InterestPaidHandler {
  private readonly logger = new Logger(InterestPaidHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('InterestPaid')
  public async handle(event: {
    accountId: string
    email: string
    transactionId: string
    amount: { amount: number; currency: string }
    balanceAfter: { amount: number; currency: string }
  }): Promise<void> {
    this.logger.log({
      message: '이자 지급 완료',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'InterestPaid',
      recipient: event.email,
      subject: '[Account] 이자가 지급되었습니다',
      body: `${event.amount.amount} ${event.amount.currency}의 이자가 지급되었습니다. 지급 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}`
    })
  }
}
