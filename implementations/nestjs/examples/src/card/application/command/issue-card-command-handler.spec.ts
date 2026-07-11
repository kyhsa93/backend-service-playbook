import { Test } from '@nestjs/testing'

import { IssueCardCommandHandler } from '@/card/application/command/issue-card-command-handler'
import { IssueCardCommand } from '@/card/application/command/issue-card-command'
import { AccountAdapter } from '@/card/application/adapter/account-adapter'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'
import { CardErrorMessage } from '@/card/card-error-message'
import { TransactionManager } from '@/database/transaction-manager'

describe('IssueCardCommandHandler', () => {
  let handler: IssueCardCommandHandler
  let cardRepository: jest.Mocked<CardRepository>
  let accountAdapter: jest.Mocked<AccountAdapter>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        IssueCardCommandHandler,
        { provide: CardRepository, useValue: { findCards: jest.fn(), saveCard: jest.fn() } },
        { provide: AccountAdapter, useValue: { findAccount: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(IssueCardCommandHandler)
    cardRepository = module.get(CardRepository)
    accountAdapter = module.get(AccountAdapter)
  })

  it('execute_when_활성_계좌_then_카드를_발급하고_저장한다', async () => {
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: true })

    const card = await handler.execute(
      new IssueCardCommand({ accountId: 'account-1', brand: 'VISA', requesterId: 'owner-1' })
    )

    expect(accountAdapter.findAccount).toHaveBeenCalledWith({ accountId: 'account-1', ownerId: 'owner-1' })
    expect(card.status).toBe(CardStatus.ACTIVE)
    expect(card.accountId).toBe('account-1')
    expect(cardRepository.saveCard).toHaveBeenCalledWith(card)
  })

  it('execute_when_계좌가_없으면_then_에러를_throw한다', async () => {
    accountAdapter.findAccount.mockResolvedValue(null)

    await expect(handler.execute(
      new IssueCardCommand({ accountId: 'missing', brand: 'VISA', requesterId: 'owner-1' })
    )).rejects.toThrow(CardErrorMessage['연결할 계좌를 찾을 수 없습니다.'])
    expect(cardRepository.saveCard).not.toHaveBeenCalled()
  })

  it('execute_when_계좌가_비활성이면_then_에러를_throw한다', async () => {
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: false })

    await expect(handler.execute(
      new IssueCardCommand({ accountId: 'account-1', brand: 'VISA', requesterId: 'owner-1' })
    )).rejects.toThrow(CardErrorMessage['활성 상태의 계좌만 카드를 발급할 수 있습니다.'])
    expect(cardRepository.saveCard).not.toHaveBeenCalled()
  })
})
