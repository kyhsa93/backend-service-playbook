import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelCardsByAccountCommand } from '@/card/application/command/cancel-cards-by-account-command'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'

// The reacting use case for Account BC's account.closed.v1 Integration Event.
// It's idempotent under re-receipt since it only cancels cards that aren't already cancelled (ACTIVE·SUSPENDED).
@CommandHandler(CancelCardsByAccountCommand)
export class CancelCardsByAccountCommandHandler implements ICommandHandler<CancelCardsByAccountCommand> {
  constructor(
    private readonly cardRepository: CardRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelCardsByAccountCommand): Promise<void> {
    const { cards } = await this.cardRepository.findCards({
      accountId: command.accountId,
      status: [CardStatus.ACTIVE, CardStatus.SUSPENDED],
      take: 1000,
      page: 0
    })
    if (cards.length === 0) return

    await this.transactionManager.run(async () => {
      for (const card of cards) {
        card.cancel()
        await this.cardRepository.saveCard(card)
      }
    })
  }
}
