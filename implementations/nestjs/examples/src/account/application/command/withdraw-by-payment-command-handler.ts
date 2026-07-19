import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { WithdrawByPaymentCommand } from '@/account/application/command/withdraw-by-payment-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'

// Payment BC의 payment.completed.v1 Integration Event에 대한 반응 유스케이스 —
// 결제 시점에 이미 동기 Adapter로 판정된 차감을 여기서 실제로 수행한다.
//
// 멱등성: WithdrawCommandHandler(사용자 직접 출금)와 달리 이 반응은 같은 referenceId
// (paymentId)의 거래가 이미 있으면 조용히 무시한다 — Card의 상태 기반 멱등성과 달리
// 금액 이동은 반복 적용하면 잔액이 계속 줄어들므로 "이미 처리했는지"를 확인해야 한다
// (Level 2 Ledger, docs/architecture/domain-events.md 참고).
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
    if (!account) return // 반응할 대상 계좌가 없으면 조용히 무시한다(예: 계좌가 이미 삭제됨).

    account.withdraw(new Money({ amount: command.amount, currency: account.balance.currency }), command.referenceId)
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }
}
