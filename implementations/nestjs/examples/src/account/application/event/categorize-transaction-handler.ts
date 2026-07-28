import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { TransactionAutoCategorizer } from '@/account/application/service/transaction-auto-categorizer'
import { TransactionRepository } from '@/account/domain/transaction-repository'

// Reacts to MoneyWithdrawn (registered in account-module.ts alongside MoneyWithdrawnHandler —
// EventHandlerRegistry supports multiple subscribers per event type) to categorize the
// transaction's merchantName asynchronously, off the money-movement hot path — the same
// reasoning WithdrawCommandHandler never calls an LLM directly. Inherently Level-1 idempotent
// (see docs/architecture/domain-events.md): a retried delivery just re-runs the same
// find→categorize→save cycle, landing on the same (or an equally acceptable) category.
@Injectable()
export class CategorizeTransactionHandler {
  private readonly logger = new Logger(CategorizeTransactionHandler.name)

  constructor(
    private readonly transactionAutoCategorizer: TransactionAutoCategorizer,
    private readonly transactionRepository: TransactionRepository
  ) {}

  @HandleEvent('MoneyWithdrawn')
  public async handle(event: {
    transactionId: string
    amount: { amount: number; currency: string }
    merchantName?: string
  }): Promise<void> {
    // Nothing to classify — the requester didn't attach a merchantName to this withdrawal.
    if (!event.merchantName) return

    const transaction = await this.transactionRepository.findTransaction(event.transactionId)
    if (!transaction) return

    const category = await this.transactionAutoCategorizer.categorize({
      merchantName: event.merchantName,
      amount: event.amount.amount
    })
    await this.transactionRepository.saveTransaction(transaction.categorize(category))
    this.logger.log({ message: 'Transaction categorized', transaction_id: event.transactionId, category })
  }
}
