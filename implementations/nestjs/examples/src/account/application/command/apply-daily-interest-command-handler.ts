import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountStatus } from '@/account/account-enum'

const PAGE_SIZE = 100

// The Command the account.apply-daily-interest Task Controller delegates to. It paginates
// through every ACTIVE account and calls Account.applyInterest() (Level 1 idempotency) —
// since an account already processed today is skipped inside the Aggregate itself, no
// separate conditional branch is added here (both the Task Controller and the Command keep
// "delegate with no logic").
@CommandHandler(ApplyDailyInterestCommand)
export class ApplyDailyInterestCommandHandler implements ICommandHandler<ApplyDailyInterestCommand, number> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: ApplyDailyInterestCommand): Promise<number> {
    let creditedCount = 0
    let page = 0

    while (true) {
      const { accounts } = await this.accountRepository.findAccounts({
        status: [AccountStatus.ACTIVE],
        take: PAGE_SIZE,
        page
      })
      if (accounts.length === 0) break

      for (const account of accounts) {
        const transaction = account.applyInterest(command.today)
        // applyInterest updates lastInterestPaidAt for an ACTIVE account even when the
        // interest is 0, so it's always saved (so a Task re-received today doesn't recompute it).
        await this.transactionManager.run(async () => {
          await this.accountRepository.saveAccount(account)
        })
        if (transaction) creditedCount++
      }

      page++
    }

    return creditedCount
  }
}
