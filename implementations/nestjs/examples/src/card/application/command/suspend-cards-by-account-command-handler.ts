import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { SuspendCardsByAccountCommand } from '@/card/application/command/suspend-cards-by-account-command'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'

// The reacting use case for Account BC's account.suspended.v1 Integration Event.
// Implemented idempotently, assuming at-least-once delivery — since it only selects and
// suspends ACTIVE cards, nothing happens even if the same event is re-received (the card is already suspended).
@CommandHandler(SuspendCardsByAccountCommand)
export class SuspendCardsByAccountCommandHandler implements ICommandHandler<SuspendCardsByAccountCommand> {
  constructor(
    private readonly cardRepository: CardRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: SuspendCardsByAccountCommand): Promise<void> {
    const { cards } = await this.cardRepository.findCards({
      accountId: command.accountId,
      status: [CardStatus.ACTIVE],
      take: 1000,
      page: 0
    })
    if (cards.length === 0) return

    await this.transactionManager.run(async () => {
      for (const card of cards) {
        card.suspend()
        await this.cardRepository.saveCard(card)
      }
    })
  }
}
