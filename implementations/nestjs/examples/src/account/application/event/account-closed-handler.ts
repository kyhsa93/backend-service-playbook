import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { AccountClosedIntegrationEventV1 } from '@/account/application/integration-event/account-closed-integration-event'
import { NotificationService } from '@/notification/notification-service'

@Injectable()
export class AccountClosedHandler {
  private readonly logger = new Logger(AccountClosedHandler.name)

  constructor(
    private readonly notificationService: NotificationService,
    private readonly outboxWriter: OutboxWriter
  ) {}

  @HandleEvent('AccountClosed')
  public async handle(event: { accountId: string; email: string; closedAt: string }): Promise<void> {
    this.logger.log({ message: '계좌 종료됨', account_id: event.accountId })

    // 외부 BC용 Integration Event(account.closed.v1)를 Outbox에 적재한다.
    await this.outboxWriter.saveAll([
      new AccountClosedIntegrationEventV1(event.accountId, event.closedAt ?? new Date().toISOString())
    ])

    // 알림은 best-effort다(정지 핸들러와 동일한 이유 — Integration Event 중복 발행 방지).
    try {
      await this.notificationService.sendEmail({
        accountId: event.accountId,
        eventType: 'AccountClosed',
        recipient: event.email,
        subject: '[Account] 계좌가 해지되었습니다',
        body: `계좌(${event.accountId})가 해지되었습니다.`
      })
    } catch (error) {
      this.logger.error({ message: '해지 알림 발송 실패', account_id: event.accountId, error })
    }
  }
}
