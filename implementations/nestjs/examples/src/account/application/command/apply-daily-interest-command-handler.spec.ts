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

  it('execute_when_ACTIVE_계좌에_이자가_발생하면_then_모든_계좌를_저장하고_지급건수를_반환한다', async () => {
    const highBalance = buildAccount('account-1', 1_000_000)
    const zeroInterest = buildAccount('account-2', 100)
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [highBalance, zeroInterest], count: 2 })
      .mockResolvedValueOnce({ accounts: [], count: 0 })

    const creditedCount = await handler.execute(new ApplyDailyInterestCommand({ today: new Date('2026-07-20T00:00:00.000Z') }))

    expect(creditedCount).toBe(1)
    expect(highBalance.balance.amount).toBe(1_000_100)
    expect(zeroInterest.balance.amount).toBe(100)
    // 이자가 0이어도 lastInterestPaidAt 갱신을 위해 저장은 두 계좌 모두 호출된다.
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(highBalance)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(zeroInterest)
    expect(accountRepository.saveAccount).toHaveBeenCalledTimes(2)
  })

  it('execute_when_두_페이지에_걸쳐_계좌가_있으면_then_모든_페이지를_순회한다', async () => {
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

  it('execute_when_지급할_계좌가_없으면_then_0을_반환하고_저장하지_않는다', async () => {
    accountRepository.findAccounts.mockResolvedValueOnce({ accounts: [], count: 0 })

    const creditedCount = await handler.execute(new ApplyDailyInterestCommand({ today: new Date('2026-07-20T00:00:00.000Z') }))

    expect(creditedCount).toBe(0)
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
