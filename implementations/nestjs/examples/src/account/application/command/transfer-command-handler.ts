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
  // Just like RefundEligibilityService, this is a plain Domain Service with no framework
  // decorators. It's instantiated directly rather than registered in the NestJS DI container.
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

    // Look up target without an owner filter — since transferring to someone else's account is
    // the whole point of this feature, only existence + active status need to be checked
    // (the ownership check applies only to source).
    const target = await this.accountRepository
      .findAccounts({ accountId: command.targetAccountId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!target) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    const amount = new Money({ amount: command.amount, currency: source.balance.currency })
    const decision = this.transferEligibilityService.evaluate(source, target, amount)
    if (!decision.approved) throw new Error(decision.reason)

    // Rather than introducing a new persistent Aggregate dedicated to this transfer,
    // transferId is used only as the referenceId that correlates the two Transaction rows.
    // Since the (reference_id, type) combination is already unique, the source (WITHDRAWAL)
    // and target (DEPOSIT) rows sharing the same transferId doesn't collide. It's used as the
    // raw 32-character value with no suffix — since the referenceId column is VARCHAR(36),
    // adding a suffix could exceed that limit.
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
