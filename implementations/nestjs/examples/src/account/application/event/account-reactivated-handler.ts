import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/account/application/service/notification-service'

@Injectable()
export class AccountReactivatedHandler {
  private readonly logger = new Logger(AccountReactivatedHandler.name)

  constructor(private readonly notificationService: NotificationService) {}

  @HandleEvent('AccountReactivated')
  public async handle(event: { accountId: string; email: string }): Promise<void> {
    this.logger.log({ message: '계좌 재개됨', account_id: event.accountId })
    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'AccountReactivated',
      recipient: event.email,
      subject: '[Account] 계좌가 재개되었습니다',
      body: `계좌(${event.accountId})가 재개되었습니다.`
    })
  }
}
