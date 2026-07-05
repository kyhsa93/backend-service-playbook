import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/notification/notification-service'

@Injectable()
export class AccountClosedHandler {
  private readonly logger = new Logger(AccountClosedHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('AccountClosed')
  public async handle(event: { accountId: string; email: string }): Promise<void> {
    this.logger.log({ message: '계좌 종료됨', account_id: event.accountId })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'AccountClosed',
      recipient: event.email,
      subject: '[Account] 계좌가 해지되었습니다',
      body: `계좌(${event.accountId})가 해지되었습니다.`
    })
  }
}
