import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class AccountCreatedHandler {
  private readonly logger = new Logger(AccountCreatedHandler.name)

  @HandleEvent('AccountCreated')
  public async handle(event: { accountId: string; ownerId: string; currency: string }): Promise<void> {
    this.logger.log({ message: '계좌 생성됨', account_id: event.accountId, owner_id: event.ownerId, currency: event.currency })
  }
}
