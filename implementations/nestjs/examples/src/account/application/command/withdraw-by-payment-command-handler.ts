import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { WithdrawByPaymentCommand } from '@/account/application/command/withdraw-by-payment-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'

// The reacting use case for Payment BC's payment.completed.v1 Integration Event —
// it actually performs here the debit that was already judged via the synchronous Adapter at
// payment time.
//
// Idempotency: unlike WithdrawCommandHandler (a user's direct withdrawal), this reaction
// silently ignores it if a transaction with the same referenceId (paymentId) already exists —
// unlike Card's state-based idempotency, moving money keeps decreasing the balance if
// reapplied, so it must check "was this already processed"
// (a Level 2 Ledger, see docs/architecture/domain-events.md).
@CommandHandler(WithdrawByPaymentCommand)
export class WithdrawByPaymentCommandHandler implements ICommandHandler<WithdrawByPaymentCommand, void> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: WithdrawByPaymentCommand): Promise<void> {
    const alreadyProcessed = await this.accountRepository.hasTransactionWithReference(command.referenceId, 'WITHDRAWAL')
    if (alreadyProcessed) return

    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) return // silently ignore if there's no account to react against (e.g. it was already deleted)

    account.withdraw(new Money({ amount: command.amount, currency: account.balance.currency }), command.referenceId)
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }
}
