import { Test } from '@nestjs/testing'

import { DepositCommandHandler } from '@/account/application/command/deposit-command-handler'
import { DepositCommand } from '@/account/application/command/deposit-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { TransactionManager } from '@/database/transaction-manager'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'

describe('DepositCommandHandler', () => {
  let handler: DepositCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>

  const buildAccount = (balance = 0): Account => new Account({
    accountId: 'account-1',
    ownerId: 'owner-1',
    email: 'owner1@example.com',
    balance: new Money({ amount: balance, currency: 'KRW' }),
    status: AccountStatus.ACTIVE
  })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        DepositCommandHandler,
        {
          provide: AccountRepository,
          useValue: { findAccounts: jest.fn(), saveAccount: jest.fn() }
        },
        {
          provide: TransactionManager,
          useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(DepositCommandHandler)
    accountRepository = module.get(AccountRepository)
  })

  it('execute_when_the_account_exists_then_the_balance_increases_and_is_saved', async () => {
    const account = buildAccount(1000)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    const transaction = await handler.execute(
      new DepositCommand({ accountId: 'account-1', requesterId: 'owner-1', amount: 500 })
    )

    expect(transaction.type).toBe('DEPOSIT')
    expect(account.balance.amount).toBe(1500)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
  })

  it('execute_when_the_account_does_not_exist_then_throws', async () => {
    accountRepository.findAccounts.mockResolvedValue({ accounts: [], count: 0 })

    await expect(
      handler.execute(new DepositCommand({ accountId: 'non-existent', requesterId: 'owner-1', amount: 500 }))
    ).rejects.toThrow('Account not found.')
  })

  it('execute_when_the_account_is_suspended_then_throws_and_does_not_save', async () => {
    const account = buildAccount(1000)
    account.suspend()
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    await expect(
      handler.execute(new DepositCommand({ accountId: 'account-1', requesterId: 'owner-1', amount: 500 }))
    ).rejects.toThrow('Only an active account can accept a deposit.')
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
