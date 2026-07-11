import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelCardsByAccountCommand } from '@/card/application/command/cancel-cards-by-account-command'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'

// Account BC의 account.closed.v1 Integration Event에 대한 반응 유스케이스.
// 아직 해지되지 않은 카드(ACTIVE·SUSPENDED)만 해지하므로 재수신에 멱등하다.
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
