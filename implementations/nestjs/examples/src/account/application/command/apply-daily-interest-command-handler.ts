import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountStatus } from '@/account/account-enum'

const PAGE_SIZE = 100

// account.apply-daily-interest Task Controller가 위임하는 Command다. 모든 ACTIVE
// 계좌를 페이지 단위로 순회하며 Account.applyInterest()(Level 1 멱등성)를 호출한다 —
// 이미 오늘 처리된 계좌는 Aggregate 내부에서 스킵되므로 여기서는 별도 조건 분기를
// 두지 않는다(Task Controller/Command 모두 "로직 없이 위임"을 유지).
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
        // applyInterest는 ACTIVE 계좌라면 이자가 0이어도 lastInterestPaidAt을 갱신하므로
        // (오늘 재수신된 Task가 다시 계산하지 않도록) 언제나 저장한다.
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
