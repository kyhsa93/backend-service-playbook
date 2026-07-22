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
    if (!account) throw new Error(ErrorMessage['The account to link could not be found.'])
    if (!account.active) throw new Error(ErrorMessage['Only an active account can have a card issued.'])

    const card = Card.issue({ accountId: command.accountId, ownerId: command.requesterId, brand: command.brand })
    await this.transactionManager.run(async () => {
      await this.cardRepository.saveCard(card)
    })
    return card
  }
}
