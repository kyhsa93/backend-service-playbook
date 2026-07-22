import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { AccountAdapter } from '@/card/application/adapter/account-adapter'
import { IssueCardCommand } from '@/card/application/command/issue-card-command'
import { Card } from '@/card/domain/card'
import { CardRepository } from '@/card/domain/card-repository'
import { CardErrorMessage as ErrorMessage } from '@/card/card-error-message'

@CommandHandler(IssueCardCommand)
export class IssueCardCommandHandler implements ICommandHandler<IssueCardCommand, Card> {
  constructor(
    private readonly cardRepository: CardRepository,
    private readonly accountAdapter: AccountAdapter,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: IssueCardCommand): Promise<Card> {
    // Look up the linked account via the synchronous Adapter (ACL) — a synchronous call since it's needed for the response (whether issuance is allowed).
    const account = await this.accountAdapter.findAccount({
      accountId: command.accountId,
      ownerId: command.requesterId
    })
    if (!account) throw new Error(ErrorMessage['연결할 계좌를 찾을 수 없습니다.'])
    if (!account.active) throw new Error(ErrorMessage['활성 상태의 계좌만 카드를 발급할 수 있습니다.'])

    const card = Card.issue({ accountId: command.accountId, ownerId: command.requesterId, brand: command.brand })
    await this.transactionManager.run(async () => {
      await this.cardRepository.saveCard(card)
    })
    return card
  }
}
