import { Test } from '@nestjs/testing'

import { ApplyDailyInterestCommandHandler } from '@/account/application/command/apply-daily-interest-command-handler'
import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { TransactionManager } from '@/database/transaction-manager'

describe('ApplyDailyInterestCommandHandler', () => {
  let handler: ApplyDailyInterestCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>

  const buildAccount = (accountId: string, balance: number): Account => new Account({
    accountId,
    ownerId: 'owner-1',
    email: 'owner1@example.com',
    balance: new Money({ amount: balance, currency: 'KRW' }),
    status: AccountStatus.ACTIVE
  })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        ApplyDailyInterestCommandHandler,
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

    handler = module.get(ApplyDailyInterestCommandHandler)
    accountRepository = module.get(AccountRepository)
  })

  it('execute_when_interest_accrues_on_ACTIVE_accounts_then_saves_all_accounts_and_returns_the_credited_count', async () => {
    const highBalance = buildAccount('account-1', 1_000_000)
    const zeroInterest = buildAccount('account-2', 100)
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [highBalance, zeroInterest], count: 2 })
      .mockResolvedValueOnce({ accounts: [], count: 0 })

    const creditedCount = await handler.execute(new ApplyDailyInterestCommand({ today: new Date('2026-07-20T00:00:00.000Z') }))

    expect(creditedCount).toBe(1)
    expect(highBalance.balance.amount).toBe(1_000_100)
    expect(zeroInterest.balance.amount).toBe(100)
    // Even when the interest is 0, save is called for both accounts to update lastInterestPaidAt.
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(highBalance)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(zeroInterest)
    expect(accountRepository.saveAccount).toHaveBeenCalledTimes(2)
  })

  it('execute_when_accounts_span_two_pages_then_iterates_through_all_pages', async () => {
    const page0 = Array.from({ length: 100 }, (_, i) => buildAccount(`account-${i}`, 1_000_000))
    const page1 = [buildAccount('account-101', 1_000_000)]
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: page0, count: 101 })
      .mockResolvedValueOnce({ accounts: page1, count: 101 })
      .mockResolvedValueOnce({ accounts: [], count: 101 })

    const creditedCount = await handler.execute(new ApplyDailyInterestCommand({ today: new Date('2026-07-20T00:00:00.000Z') }))

    expect(creditedCount).toBe(101)
    expect(accountRepository.findAccounts).toHaveBeenCalledTimes(3)
  })

  it('execute_when_there_are_no_accounts_to_pay_then_returns_0_and_does_not_save', async () => {
    accountRepository.findAccounts.mockResolvedValueOnce({ accounts: [], count: 0 })

    const creditedCount = await handler.execute(new ApplyDailyInterestCommand({ today: new Date('2026-07-20T00:00:00.000Z') }))

    expect(creditedCount).toBe(0)
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
