import { Test } from '@nestjs/testing'

import { TransactionManager } from '@/database/transaction-manager'
import { TransferCommand } from '@/account/application/command/transfer-command'
import { TransferCommandHandler } from '@/account/application/command/transfer-command-handler'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { AccountErrorMessage } from '@/account/account-error-message'

// TransferEligibilityService(Domain Service)는 순수 클래스라 목(mock)하지 않는다 —
// 이 스펙은 Application 레이어가 두 계좌를 로드해 실제 판단 로직에 위임하고, 승인 시
// 두 계좌를 하나의 트랜잭션으로 저장하는 흐름을 검증한다.
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

  it('execute_when_승인되면_then_두_계좌를_저장한다', async () => {
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

  it('execute_when_출금_계좌를_찾을_수_없으면_then_에러를_throw하고_저장하지_않는다', async () => {
    accountRepository.findAccounts.mockResolvedValueOnce({ accounts: [], count: 0 })

    await expect(handler.execute(
      new TransferCommand({ sourceAccountId: 'missing', targetAccountId: 'account-2', amount: 5000, requesterId: 'owner-1' })
    )).rejects.toThrow(AccountErrorMessage['계좌를 찾을 수 없습니다.'])
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })

  it('execute_when_잔액이_부족하면_then_에러를_throw하고_저장하지_않는다', async () => {
    const source = createAccount({ accountId: 'account-1', amount: 1000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [source], count: 1 })
      .mockResolvedValueOnce({ accounts: [target], count: 1 })

    await expect(handler.execute(
      new TransferCommand({ sourceAccountId: 'account-1', targetAccountId: 'account-2', amount: 5000, requesterId: 'owner-1' })
    )).rejects.toThrow(AccountErrorMessage['잔액이 부족합니다.'])
    expect(accountRepository.saveAccount).not.toHaveBeenCalled()
  })
})
