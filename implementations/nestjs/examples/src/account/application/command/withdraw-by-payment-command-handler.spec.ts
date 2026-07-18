import { Test } from '@nestjs/testing'

import { WithdrawByPaymentCommandHandler } from '@/account/application/command/withdraw-by-payment-command-handler'
import { WithdrawByPaymentCommand } from '@/account/application/command/withdraw-by-payment-command'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { OutboxRelay } from '@/account/application/event/outbox-relay'
import { TransactionManager } from '@/database/transaction-manager'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'

describe('WithdrawByPaymentCommandHandler', () => {
  let handler: WithdrawByPaymentCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>
  let outboxRelay: jest.Mocked<OutboxRelay>

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
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } },
        { provide: OutboxRelay, useValue: { processPending: jest.fn() } }
      ]
    }).compile()

    handler = module.get(WithdrawByPaymentCommandHandler)
    accountRepository = module.get(AccountRepository)
    outboxRelay = module.get(OutboxRelay)
  })

  it('execute_when_처음_수신하면_then_잔액을_차감하고_저장한다', async () => {
    const account = buildAccount(10000)
    accountRepository.hasTransactionWithReference.mockResolvedValue(false)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [account], count: 1 })

    await handler.execute(new WithdrawByPaymentCommand({ accountId: 'account-1', amount: 5000, referenceId: 'payment-1' }))

    expect(account.balance.amount).toBe(5000)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
    expect(outboxRelay.processPending).toHaveBeenCalled()
  })

  it('execute_when_같은_referenceId를_이미_처리했으면_then_아무_일도_하지_않는다', async () => {
    accountRepository.hasTransactionWithReference.mockResolvedValue(true)

    await handler.execute(new WithdrawByPaymentCommand({ accountId: 'account-1', amount: 5000, referenceId: 'payment-1' }))

    expect(accountRepository.findAccounts).not.toHaveBeenCalled()
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })

  it('execute_when_계좌가_없으면_then_아무_일도_하지_않는다', async () => {
    accountRepository.hasTransactionWithReference.mockResolvedValue(false)
    accountRepository.findAccounts.mockResolvedValue({ accounts: [], count: 0 })

    await handler.execute(new WithdrawByPaymentCommand({ accountId: 'missing', amount: 5000, referenceId: 'payment-1' }))

    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
