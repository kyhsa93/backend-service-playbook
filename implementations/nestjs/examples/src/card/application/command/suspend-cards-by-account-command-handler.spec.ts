import { Test } from '@nestjs/testing'

import { SuspendCardsByAccountCommandHandler } from '@/card/application/command/suspend-cards-by-account-command-handler'
import { SuspendCardsByAccountCommand } from '@/card/application/command/suspend-cards-by-account-command'
import { Card } from '@/card/domain/card'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'
import { TransactionManager } from '@/database/transaction-manager'

describe('SuspendCardsByAccountCommandHandler', () => {
  let handler: SuspendCardsByAccountCommandHandler
  let cardRepository: jest.Mocked<CardRepository>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        SuspendCardsByAccountCommandHandler,
        { provide: CardRepository, useValue: { findCards: jest.fn(), saveCard: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(SuspendCardsByAccountCommandHandler)
    cardRepository = module.get(CardRepository)
  })

  it('execute_when_활성_카드가_있으면_then_모두_정지하고_저장한다', async () => {
    const card = new Card({ accountId: 'account-1', ownerId: 'owner-1', brand: 'VISA', status: CardStatus.ACTIVE })
    cardRepository.findCards.mockResolvedValue({ cards: [card], count: 1 })

    await handler.execute(new SuspendCardsByAccountCommand({ accountId: 'account-1' }))

    expect(cardRepository.findCards).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'account-1', status: [CardStatus.ACTIVE] })
    )
    expect(card.status).toBe(CardStatus.SUSPENDED)
    expect(cardRepository.saveCard).toHaveBeenCalledWith(card)
  })

  it('execute_when_활성_카드가_없으면_then_아무것도_하지_않는다_멱등', async () => {
    cardRepository.findCards.mockResolvedValue({ cards: [], count: 0 })

    await handler.execute(new SuspendCardsByAccountCommand({ accountId: 'account-1' }))

    expect(cardRepository.saveCard).not.toHaveBeenCalled()
  })
})
