import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class AccountReactivatedHandler {
  private readonly logger = new Logger(AccountReactivatedHandler.name)

  @HandleEvent('AccountReactivated')
  public async handle(event: { accountId: string }): Promise<void> {
    this.logger.log({ message: '계좌 재개됨', account_id: event.accountId })
  }
}
