import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { DepositByPaymentCommand } from '@/account/application/command/deposit-by-payment-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'

// The reacting use case for both of Payment BC's payment.cancelled.v1 (a payment-cancellation
// compensating credit) and refund.approved.v1 (a refund-approval credit) Integration Events —
// both events perform the identical action of "reversing an amount that was already debited,"
// differing only in referenceId (paymentId or refundId), so a single Command is reused for both.
//
// Idempotency uses a Level 2 Ledger, for the same reason as WithdrawByPaymentCommandHandler.
@CommandHandler(DepositByPaymentCommand)
export class DepositByPaymentCommandHandler implements ICommandHandler<DepositByPaymentCommand, void> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: DepositByPaymentCommand): Promise<void> {
    const alreadyProcessed = await this.accountRepository.hasTransactionWithReference(command.referenceId, 'DEPOSIT')
    if (alreadyProcessed) return

    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) return

    account.deposit(new Money({ amount: command.amount, currency: account.balance.currency }), command.referenceId)
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }
}
