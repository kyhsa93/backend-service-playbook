import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { AccountClosedIntegrationEventV1 } from '@/account/application/integration-event/account-closed-integration-event'
import { NotificationService } from '@/account/application/service/notification-service'

@Injectable()
export class AccountClosedHandler {
  private readonly logger = new Logger(AccountClosedHandler.name)

  constructor(
    private readonly notificationService: NotificationService,
    private readonly outboxWriter: OutboxWriter
  ) {}

  @HandleEvent('AccountClosed')
  public async handle(event: { accountId: string; email: string; closedAt: string }): Promise<void> {
    this.logger.log({ message: 'Account closed', account_id: event.accountId })

    // Write the external-BC Integration Event (account.closed.v1) to the Outbox.
    await this.outboxWriter.saveAll([
      new AccountClosedIntegrationEventV1(event.accountId, event.closedAt ?? new Date().toISOString())
    ])

    // The notification is best-effort (same reason as the suspend handler — avoids duplicate-publishing the Integration Event).
    try {
      await this.notificationService.sendEmail({
        accountId: event.accountId,
        eventType: 'AccountClosed',
        recipient: event.email,
        subject: '[Account] Your account has been closed',
        body: `Account (${event.accountId}) has been closed.`
      })
    } catch (error) {
      this.logger.error({ message: 'Failed to send closure notification', account_id: event.accountId, error })
    }
  }
}
