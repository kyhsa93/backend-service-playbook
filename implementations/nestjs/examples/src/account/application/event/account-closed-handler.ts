import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class AccountClosedHandler {
  private readonly logger = new Logger(AccountClosedHandler.name)

  @HandleEvent('AccountClosed')
  public async handle(event: { accountId: string }): Promise<void> {
    this.logger.log({ message: '계좌 종료됨', account_id: event.accountId })
  }
}
