import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/notification/notification-service'

@Injectable()
export class AccountSuspendedHandler {
  private readonly logger = new Logger(AccountSuspendedHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('AccountSuspended')
  public async handle(event: { accountId: string; email: string }): Promise<void> {
    this.logger.log({ message: '계좌 정지됨', account_id: event.accountId })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'AccountSuspended',
      recipient: event.email,
      subject: '[Account] 계좌가 정지되었습니다',
      body: `계좌(${event.accountId})가 정지되었습니다.`
    })
  }
}
