import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class AccountSuspendedHandler {
  private readonly logger = new Logger(AccountSuspendedHandler.name)

  @HandleEvent('AccountSuspended')
  public async handle(event: { accountId: string }): Promise<void> {
    this.logger.log({ message: '계좌 정지됨', account_id: event.accountId })
  }
}
