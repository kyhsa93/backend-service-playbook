import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { AccountSuspendedIntegrationEventV1 } from '@/account/application/integration-event/account-suspended-integration-event'
import { NotificationService } from '@/account/application/service/notification-service'

// An Application EventHandler that receives the internal Domain Event (AccountSuspended) and
// performs follow-up processing. An EventHandler in application/event/ is the one exception
// allowed to use OutboxWriter directly, and here it converts it into an external-BC
// Integration Event (account.suspended.v1) and writes it to the Outbox
// (the Aggregate never creates an Integration Event directly — the conversion point is always the EventHandler).
@Injectable()
export class AccountSuspendedHandler {
  private readonly logger = new Logger(AccountSuspendedHandler.name)

  constructor(
    private readonly notificationService: NotificationService,
    private readonly outboxWriter: OutboxWriter
  ) {}

  @HandleEvent('AccountSuspended')
  public async handle(event: { accountId: string; email: string; suspendedAt: string }): Promise<void> {
    this.logger.log({ message: '계좌 정지됨', account_id: event.accountId })

    // Write the Integration Event notifying external BCs (Card, etc.) to the Outbox.
    await this.outboxWriter.saveAll([
      new AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt ?? new Date().toISOString())
    ])

    // The notification is best-effort. Even on failure, the handler doesn't throw — throwing
    // would cause this outbox row to be re-drained, duplicate-publishing the Integration Event
    // above (harmless since the receiving side is idempotent, but this avoids unnecessary
    // amplification). Retrying the notification itself is the job of a separate outbox row (the sent_email pipeline).
    try {
      await this.notificationService.sendEmail({
        accountId: event.accountId,
        eventType: 'AccountSuspended',
        recipient: event.email,
        subject: '[Account] 계좌가 정지되었습니다',
        body: `계좌(${event.accountId})가 정지되었습니다.`
      })
    } catch (error) {
      this.logger.error({ message: '정지 알림 발송 실패', account_id: event.accountId, error })
    }
  }
}
