import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/account/application/service/notification-service'

@Injectable()
export class AccountCreatedHandler {
  private readonly logger = new Logger(AccountCreatedHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('AccountCreated')
  public async handle(event: { accountId: string; ownerId: string; email: string; currency: string }): Promise<void> {
    this.logger.log({ message: 'Account created', account_id: event.accountId, owner_id: event.ownerId, currency: event.currency })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'AccountCreated',
      recipient: event.email,
      subject: '[Account] Your account has been opened',
      body: `Account (${event.accountId}) has been opened. Currency: ${event.currency}`
    })
  }
}
