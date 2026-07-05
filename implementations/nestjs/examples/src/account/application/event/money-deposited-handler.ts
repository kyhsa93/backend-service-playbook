import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class MoneyDepositedHandler {
  private readonly logger = new Logger(MoneyDepositedHandler.name)

  @HandleEvent('MoneyDeposited')
  public async handle(event: { accountId: string; transactionId: string; amount: { amount: number; currency: string } }): Promise<void> {
    this.logger.log({
      message: '입금 완료',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount,
      currency: event.amount.currency
    })
  }
}
