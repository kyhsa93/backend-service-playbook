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

  it('execute_when_계좌가_존재하면_then_잔액이_증가하고_저장된다', async () => {
    const account = buildAccount(1000)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    const transaction = await handler.execute(
      new DepositCommand({ accountId: 'account-1', requesterId: 'owner-1', amount: 500 })
    )

    expect(transaction.type).toBe('DEPOSIT')
    expect(account.balance.amount).toBe(1500)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
  })

  it('execute_when_계좌가_존재하지_않으면_then_에러를_throw한다', async () => {
    accountRepository.findAccounts.mockResolvedValue({ accounts: [], count: 0 })

    await expect(
      handler.execute(new DepositCommand({ accountId: 'non-existent', requesterId: 'owner-1', amount: 500 }))
    ).rejects.toThrow('계좌를 찾을 수 없습니다.')
  })

  it('execute_when_정지된_계좌면_then_에러를_throw하고_저장하지_않는다', async () => {
    const account = buildAccount(1000)
    account.suspend()
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    await expect(
      handler.execute(new DepositCommand({ accountId: 'account-1', requesterId: 'owner-1', amount: 500 }))
    ).rejects.toThrow('활성 상태의 계좌만 입금할 수 있습니다.')
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
