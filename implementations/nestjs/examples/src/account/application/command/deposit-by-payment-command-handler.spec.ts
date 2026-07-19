import { Test } from '@nestjs/testing'

import { DepositByPaymentCommandHandler } from '@/account/application/command/deposit-by-payment-command-handler'
import { DepositByPaymentCommand } from '@/account/application/command/deposit-by-payment-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { TransactionManager } from '@/database/transaction-manager'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'

describe('DepositByPaymentCommandHandler', () => {
  let handler: DepositByPaymentCommandHandler
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
        DepositByPaymentCommandHandler,
        {
          provide: AccountRepository,
          useValue: { findAccounts: jest.fn(), saveAccount: jest.fn(), hasTransactionWithReference: jest.fn() }
        },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(DepositByPaymentCommandHandler)
    accountRepository = module.get(AccountRepository)
  })

  it('execute_when_처음_수신하면_then_잔액을_보상_크레딧하고_저장한다', async () => {
    const account = buildAccount(1000)
    accountRepository.hasTransactionWithReference.mockResolvedValue(false)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    await handler.execute(new DepositByPaymentCommand({ accountId: 'account-1', amount: 5000, referenceId: 'payment-1' }))

    expect(account.balance.amount).toBe(6000)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
  })

  it('execute_when_같은_referenceId를_이미_처리했으면_then_아무_일도_하지_않는다', async () => {
    accountRepository.hasTransactionWithReference.mockResolvedValue(true)

    await handler.execute(new DepositByPaymentCommand({ accountId: 'account-1', amount: 5000, referenceId: 'refund-1' }))

    expect(accountRepository.findAccounts).not.toHaveBeenCalled()
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
