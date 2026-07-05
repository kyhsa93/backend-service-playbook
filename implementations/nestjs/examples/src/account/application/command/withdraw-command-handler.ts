import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxRelay } from '@/account/application/event/outbox-relay'
import { WithdrawCommand } from '@/account/application/command/withdraw-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'
import { Transaction } from '@/account/domain/transaction'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@CommandHandler(WithdrawCommand)
export class WithdrawCommandHandler implements ICommandHandler<WithdrawCommand, Transaction> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: WithdrawCommand): Promise<Transaction> {
    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    const transaction = account.withdraw(new Money({ amount: command.amount, currency: account.balance.currency }))
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    await this.outboxRelay.processPending()
    return transaction
  }
}
