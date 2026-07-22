import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { WithdrawCommand } from '@/account/application/command/withdraw-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'
import { Transaction } from '@/account/domain/transaction'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@CommandHandler(WithdrawCommand)
export class WithdrawCommandHandler implements ICommandHandler<WithdrawCommand, Transaction> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: WithdrawCommand): Promise<Transaction> {
    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['Account not found.'])

    const transaction = account.withdraw(new Money({ amount: command.amount, currency: account.balance.currency }))
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    return transaction
  }
}
