import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/notification/notification-service'

@Injectable()
export class AccountCreatedHandler {
  private readonly logger = new Logger(AccountCreatedHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('AccountCreated')
  public async handle(event: { accountId: string; ownerId: string; email: string; currency: string }): Promise<void> {
    this.logger.log({ message: '계좌 생성됨', account_id: event.accountId, owner_id: event.ownerId, currency: event.currency })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'AccountCreated',
      recipient: event.email,
      subject: '[Account] 계좌가 개설되었습니다',
      body: `계좌(${event.accountId})가 개설되었습니다. 통화: ${event.currency}`
    })
  }
}
