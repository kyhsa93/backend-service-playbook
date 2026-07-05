import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class MoneyWithdrawnHandler {
  private readonly logger = new Logger(MoneyWithdrawnHandler.name)

  @HandleEvent('MoneyWithdrawn')
  public async handle(event: { accountId: string; transactionId: string; amount: { amount: number; currency: string } }): Promise<void> {
    this.logger.log({
      message: '출금 완료',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
  }
}
