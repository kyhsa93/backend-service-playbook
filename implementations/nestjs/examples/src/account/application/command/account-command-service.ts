import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { CloseAccountCommand } from '@/account/application/command/close-account-command'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { DepositCommand } from '@/account/application/command/deposit-command'
import { ReactivateAccountCommand } from '@/account/application/command/reactivate-account-command'
import { SuspendAccountCommand } from '@/account/application/command/suspend-account-command'
import { WithdrawCommand } from '@/account/application/command/withdraw-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { Money } from '@/account/domain/money'
import { Transaction } from '@/account/domain/transaction'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@Injectable()
export class AccountCommandService {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async createAccount(command: CreateAccountCommand): Promise<Account> {
    const account = Account.create({ ownerId: command.requesterId, currency: command.currency })
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    return account
  }

  public async deposit(command: DepositCommand): Promise<Transaction> {
    const account = await this.findOwnedAccount(command.accountId, command.requesterId)
    const transaction = account.deposit(new Money({ amount: command.amount, currency: account.balance.currency }))
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    return transaction
  }

  public async withdraw(command: WithdrawCommand): Promise<Transaction> {
    const account = await this.findOwnedAccount(command.accountId, command.requesterId)
    const transaction = account.withdraw(new Money({ amount: command.amount, currency: account.balance.currency }))
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
    return transaction
  }

  public async suspendAccount(command: SuspendAccountCommand): Promise<void> {
    const account = await this.findOwnedAccount(command.accountId, command.requesterId)
    account.suspend()
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }

  public async reactivateAccount(command: ReactivateAccountCommand): Promise<void> {
    const account = await this.findOwnedAccount(command.accountId, command.requesterId)
    account.reactivate()
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }

  public async closeAccount(command: CloseAccountCommand): Promise<void> {
    const account = await this.findOwnedAccount(command.accountId, command.requesterId)
    account.close()
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(account)
    })
  }

  private async findOwnedAccount(accountId: string, ownerId: string): Promise<Account> {
    const account = await this.accountRepository
      .findAccounts({ accountId, ownerId, take: 1, page: 0 })
      .then((r) => r.accounts.pop())
    if (!account) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])
    return account
  }
}
