import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'

@CommandHandler(CreateAccountCommand)
export class CreateAccountCommandHandler implements ICommandHandler<CreateAccountCommand, Account> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CreateAccountCommand): Promise<Account> {
    const account = Account.create({ ownerId: command.requesterId, email: command.email, currency: command.currency })
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    // Outbox 드레인은 OutboxPoller/OutboxConsumer가 독립적으로 주기 실행하며 처리한다 —
    // Command Handler는 저장이 끝나면 그대로 반환한다.
    return account
  }
}
