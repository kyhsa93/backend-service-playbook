import { Test } from '@nestjs/testing'

import { TransactionManager } from '@/database/transaction-manager'
import { TransferCommand } from '@/account/application/command/transfer-command'
import { TransferCommandHandler } from '@/account/application/command/transfer-command-handler'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { AccountErrorMessage } from '@/account/account-error-message'

// TransferEligibilityService (a Domain Service) is a plain class, so it isn't mocked — this
// spec verifies the flow where the Application layer loads both accounts, delegates to the
// real judgment logic, and on approval, saves both accounts in a single transaction.
describe('TransferCommandHandler', () => {
  let handler: TransferCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>

  const createAccount = (params: { accountId: string; status?: AccountStatus; amount?: number }): Account =>
    new Account({
      accountId: params.accountId,
      ownerId: 'owner-1',
      email: 'owner-1@example.com',
      balance: new Money({ amount: params.amount ?? 10000, currency: 'KRW' }),
      status: params.status ?? AccountStatus.ACTIVE
    })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        TransferCommandHandler,
        { provide: AccountRepository, useValue: { findAccounts: jest.fn(), saveAccount: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(TransferCommandHandler)
    accountRepository = module.get(AccountRepository)
  })

  it('execute_when_approved_then_saves_both_accounts', async () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [source], count: 1 })
      .mockResolvedValueOnce({ accounts: [target], count: 1 })

    const result = await handler.execute(
      new TransferCommand({ sourceAccountId: 'account-1', targetAccountId: 'account-2', amount: 5000, requesterId: 'owner-1' })
    )

    expect(result.sourceTransaction.type).toBe('WITHDRAWAL')
    expect(result.targetTransaction.type).toBe('DEPOSIT')
    expect(result.sourceTransaction.referenceId).toBe(result.transferId)
    expect(result.targetTransaction.referenceId).toBe(result.transferId)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(source)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(target)
    expect(accountRepository.saveAccount).toHaveBeenCalledTimes(2)
  })

  it('execute_when_the_withdrawal_account_cannot_be_found_then_throws_and_does_not_save', async () => {
    accountRepository.findAccounts.mockResolvedValueOnce({ accounts: [], count: 0 })

    await expect(handler.execute(
      new TransferCommand({ sourceAccountId: 'missing', targetAccountId: 'account-2', amount: 5000, requesterId: 'owner-1' })
    )).rejects.toThrow(AccountErrorMessage['Account not found.'])
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })

  it('execute_when_the_balance_is_insufficient_then_throws_and_does_not_save', async () => {
    const source = createAccount({ accountId: 'account-1', amount: 1000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [source], count: 1 })
      .mockResolvedValueOnce({ accounts: [target], count: 1 })

    await expect(handler.execute(
      new TransferCommand({ sourceAccountId: 'account-1', targetAccountId: 'account-2', amount: 5000, requesterId: 'owner-1' })
    )).rejects.toThrow(AccountErrorMessage['Insufficient balance.'])
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
