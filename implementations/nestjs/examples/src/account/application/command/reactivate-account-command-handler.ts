import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxRelay } from '@/account/application/event/outbox-relay'
import { ReactivateAccountCommand } from '@/account/application/command/reactivate-account-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@CommandHandler(ReactivateAccountCommand)
export class ReactivateAccountCommandHandler implements ICommandHandler<ReactivateAccountCommand> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: ReactivateAccountCommand): Promise<void> {
    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    account.reactivate()
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    await this.outboxRelay.processPending()
  }
}
