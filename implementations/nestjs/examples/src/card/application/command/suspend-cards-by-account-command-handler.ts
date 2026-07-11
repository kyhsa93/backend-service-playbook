import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { SuspendCardsByAccountCommand } from '@/card/application/command/suspend-cards-by-account-command'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'

// Account BC의 account.suspended.v1 Integration Event에 대한 반응 유스케이스.
// at-least-once 전달을 전제로 멱등하게 구현한다 — ACTIVE 카드만 골라 정지하므로
// 같은 이벤트가 재수신되어도(이미 정지된 카드) 아무 일도 하지 않는다.
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
