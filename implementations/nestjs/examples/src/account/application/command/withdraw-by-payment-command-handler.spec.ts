import { Test } from '@nestjs/testing'

import { WithdrawByPaymentCommandHandler } from '@/account/application/command/withdraw-by-payment-command-handler'
import { WithdrawByPaymentCommand } from '@/account/application/command/withdraw-by-payment-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { TransactionManager } from '@/database/transaction-manager'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'

describe('WithdrawByPaymentCommandHandler', () => {
  let handler: WithdrawByPaymentCommandHandler
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
        WithdrawByPaymentCommandHandler,
        {
          provide: AccountRepository,
          useValue: { findAccounts: jest.fn(), saveAccount: jest.fn(), hasTransactionWithReference: jest.fn() }
        },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(WithdrawByPaymentCommandHandler)
    accountRepository = module.get(AccountRepository)
  })

  it('execute_when_received_for_the_first_time_then_deducts_the_amount_from_the_balance_and_saves', async () => {
    const account = buildAccount(10000)
    accountRepository.hasTransactionWithReference.mockResolvedValue(false)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    await handler.execute(new WithdrawByPaymentCommand({ accountId: 'account-1', amount: 5000, referenceId: 'payment-1' }))

    expect(account.balance.amount).toBe(5000)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
  })

  it('execute_when_the_same_referenceId_was_already_processed_then_does_nothing', async () => {
    accountRepository.hasTransactionWithReference.mockResolvedValue(true)

    await handler.execute(new WithdrawByPaymentCommand({ accountId: 'account-1', amount: 5000, referenceId: 'payment-1' }))

    expect(accountRepository.findAccounts).not.toHaveBeenCalled()
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })

  it('execute_when_the_account_does_not_exist_then_does_nothing', async () => {
    accountRepository.hasTransactionWithReference.mockResolvedValue(false)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [], count: 0 })

    await handler.execute(new WithdrawByPaymentCommand({ accountId: 'missing', amount: 5000, referenceId: 'payment-1' }))

    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
