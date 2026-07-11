import { Injectable, Logger } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { HandleIntegrationEvent } from '@/outbox/event-handler-registry'
import { CancelCardsByAccountCommand } from '@/card/application/command/cancel-cards-by-account-command'
import { SuspendCardsByAccountCommand } from '@/card/application/command/suspend-cards-by-account-command'

// 외부 BC(Account)가 발행한 Integration Event를 수신하는 Interface 입력 어댑터.
// HTTP Controller·Task Controller와 동일한 위치(interface/)의 입력 경계다.
// 자기 도메인의 유스케이스(Command)만 호출하고, 예외는 그대로 throw하여
// OutboxRelay가 재시도를 담당하게 한다.
@Injectable()
export class CardIntegrationEventController {
  private readonly logger = new Logger(CardIntegrationEventController.name)

  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('account.suspended.v1')
  public async onAccountSuspended(event: { accountId: string }): Promise<void> {
    this.logger.log({ message: 'account.suspended.v1 수신', account_id: event.accountId })
    await this.commandBus.execute(new SuspendCardsByAccountCommand({ accountId: event.accountId }))
  }

  @HandleIntegrationEvent('account.closed.v1')
  public async onAccountClosed(event: { accountId: string }): Promise<void> {
    this.logger.log({ message: 'account.closed.v1 수신', account_id: event.accountId })
    await this.commandBus.execute(new CancelCardsByAccountCommand({ accountId: event.accountId }))
  }
}
