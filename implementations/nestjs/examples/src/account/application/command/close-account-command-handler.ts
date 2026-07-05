import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CloseAccountCommand } from '@/account/application/command/close-account-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@CommandHandler(CloseAccountCommand)
export class CloseAccountCommandHandler implements ICommandHandler<CloseAccountCommand> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CloseAccountCommand): Promise<void> {
    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    account.close()
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }
}
