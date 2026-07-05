import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxRelay } from '@/account/application/event/outbox-relay'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'

@CommandHandler(CreateAccountCommand)
export class CreateAccountCommandHandler implements ICommandHandler<CreateAccountCommand, Account> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: CreateAccountCommand): Promise<Account> {
    const account = Account.create({ ownerId: command.requesterId, email: command.email, currency: command.currency })
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    await this.outboxRelay.processPending()
    return account
  }
}
