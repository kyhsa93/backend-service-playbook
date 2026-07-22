import { Injectable, Logger } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { HandleIntegrationEvent } from '@/outbox/event-handler-registry'
import { CancelCardsByAccountCommand } from '@/card/application/command/cancel-cards-by-account-command'
import { SuspendCardsByAccountCommand } from '@/card/application/command/suspend-cards-by-account-command'

// An Interface input adapter that receives Integration Events published by an external BC
// (Account). An input boundary at the same location (interface/) as the HTTP Controller ·
// Task Controller. It calls only its own domain's use case (Command), and throws exceptions
// as-is so the OutboxConsumer doesn't delete the message and instead handles the retry.
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
