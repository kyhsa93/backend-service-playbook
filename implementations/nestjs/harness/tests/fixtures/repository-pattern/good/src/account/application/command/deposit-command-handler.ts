import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

export class DepositCommandHandler {
  constructor(private readonly accountRepository: AccountRepository) {}

  public async execute(command: { accountId: string; amount: number }): Promise<void> {
    const account = await this.accountRepository
      .findAccounts({ accountId: command.accountId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['Account not found.'])

    const amount = new Money({ amount: command.amount, currency: 'KRW' })
    await this.accountRepository.saveAccount(account)
  }
}
