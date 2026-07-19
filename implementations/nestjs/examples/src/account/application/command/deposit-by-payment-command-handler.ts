import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { DepositByPaymentCommand } from '@/account/application/command/deposit-by-payment-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'

// Payment BC의 payment.cancelled.v1(결제취소 보상 크레딧) 및 refund.approved.v1
// (환불 승인 크레딧) Integration Event 둘 다에 대한 반응 유스케이스다 — 두 이벤트는
// "이미 차감된 금액을 되돌린다"는 동일한 동작이고 referenceId(paymentId 또는
// refundId)만 다르므로 커맨드를 하나로 재사용한다.
//
// 멱등성은 WithdrawByPaymentCommandHandler와 동일한 이유로 Level 2 Ledger를 쓴다.
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
