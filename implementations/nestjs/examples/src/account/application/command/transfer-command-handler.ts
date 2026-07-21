import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { generateId } from '@/common/generate-id'
import { TransactionManager } from '@/database/transaction-manager'
import { TransferCommand } from '@/account/application/command/transfer-command'
import { TransferResult } from '@/account/application/command/transfer-result'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'
import { TransferEligibilityService } from '@/account/domain/transfer-eligibility-service'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@CommandHandler(TransferCommand)
export class TransferCommandHandler implements ICommandHandler<TransferCommand, TransferResult> {
  // RefundEligibilityService와 동일하게, 프레임워크 데코레이터가 없는 순수 Domain
  // Service다. NestJS DI 컨테이너에 등록하지 않고 직접 인스턴스화해 쓴다.
  private readonly transferEligibilityService = new TransferEligibilityService()

  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: TransferCommand): Promise<TransferResult> {
    const source = await this.accountRepository
      .findAccounts({ accountId: command.sourceAccountId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!source) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    // target은 소유자 필터 없이 조회한다 — 타인 계좌로 송금하는 것이 이 기능의 목적이라,
    // 존재 + 활성 여부만 확인하면 된다(소유권 확인은 source에만 적용).
    const target = await this.accountRepository
      .findAccounts({ accountId: command.targetAccountId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!target) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    const amount = new Money({ amount: command.amount, currency: source.balance.currency })
    const decision = this.transferEligibilityService.evaluate(source, target, amount)
    if (!decision.approved) throw new Error(decision.reason)

    // transferId는 이 송금 전용의 새 영속 Aggregate를 두지 않고, 두 Transaction 행을
    // 상관관계 짓는 referenceId로만 쓴다(reference_id, type) 조합이 이미 유니크하므로
    // source(WITHDRAWAL)/target(DEPOSIT) 두 행이 같은 transferId를 공유해도 충돌하지
    // 않는다. 접미사 없이 32자리 원본 그대로 쓴다 — referenceId 컬럼이 VARCHAR(36)이므로
    // 접미사를 붙이면 그 한도를 넘길 수 있다.
    const transferId = generateId()
    const sourceTransaction = source.withdraw(amount, transferId)
    const targetTransaction = target.deposit(amount, transferId)

    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(source)
      await this.accountRepository.saveAccount(target)
    })

    return new TransferResult(transferId, sourceTransaction, targetTransaction)
  }
}
