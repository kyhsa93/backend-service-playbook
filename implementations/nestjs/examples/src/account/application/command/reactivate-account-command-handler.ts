import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { ReactivateAccountCommand } from '@/account/application/command/reactivate-account-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@CommandHandler(ReactivateAccountCommand)
export class ReactivateAccountCommandHandler implements ICommandHandler<ReactivateAccountCommand> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: ReactivateAccountCommand): Promise<void> {
    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['Account not found.'])

    account.reactivate()
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }
}
