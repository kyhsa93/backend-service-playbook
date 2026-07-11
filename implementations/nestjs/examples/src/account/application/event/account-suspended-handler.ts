import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { AccountSuspendedIntegrationEventV1 } from '@/account/application/integration-event/account-suspended-integration-event'
import { NotificationService } from '@/account/application/service/notification-service'

// 내부 Domain Event(AccountSuspended)를 수신해 후속 처리를 수행하는 Application EventHandler.
// application/event/의 EventHandler는 OutboxWriter를 직접 사용할 수 있는 유일한 예외로,
// 여기서 외부 BC용 Integration Event(account.suspended.v1)로 변환해 Outbox에 적재한다
// (Aggregate가 Integration Event를 직접 만들지 않는다 — 변환 지점은 항상 EventHandler다).
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

    // 외부 BC(Card 등)에 알리는 Integration Event를 Outbox에 적재한다.
    await this.outboxWriter.saveAll([
      new AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt ?? new Date().toISOString())
    ])

    // 알림은 best-effort다. 실패해도 핸들러를 throw시키지 않는다 — throw하면 이 outbox 행이
    // 재드레인되어 위 Integration Event가 중복 발행되기 때문이다(수신 측이 멱등이라 무해하지만
    // 불필요한 증폭을 피한다). 알림 자체의 재시도는 별도 outbox 행(sent_email 파이프라인)의 몫이다.
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
