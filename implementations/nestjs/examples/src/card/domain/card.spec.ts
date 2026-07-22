import { Card } from '@/card/domain/card'
import { CardStatus } from '@/card/card-enum'
import { CardErrorMessage } from '@/card/card-error-message'

describe('Card', () => {
  const createCard = (status: CardStatus = CardStatus.ACTIVE): Card => new Card({
    cardId: 'card-1',
    accountId: 'account-1',
    ownerId: 'owner-1',
    brand: 'VISA',
    status
  })

  it('issue_when_valid_input_then_creates_a_card_with_ACTIVE_status', () => {
    const card = Card.issue({ accountId: 'account-1', ownerId: 'owner-1', brand: 'VISA' })
    expect(card.status).toBe(CardStatus.ACTIVE)
    expect(card.accountId).toBe('account-1')
    expect(card.ownerId).toBe('owner-1')
    expect(card.cardId).toEqual(expect.any(String))
  })

  it('suspend_when_card_is_active_then_transitions_to_SUSPENDED', () => {
    const card = createCard(CardStatus.ACTIVE)
    card.suspend()
    expect(card.status).toBe(CardStatus.SUSPENDED)
  })

  it('suspend_when_card_is_already_suspended_then_throws', () => {
    const card = createCard(CardStatus.SUSPENDED)
    expect(() => card.suspend()).toThrow(CardErrorMessage['The card is already suspended.'])
  })

  it('suspend_when_card_is_cancelled_then_throws', () => {
    const card = createCard(CardStatus.CANCELLED)
    expect(() => card.suspend()).toThrow(CardErrorMessage['A cancelled card cannot be suspended.'])
  })

  it('cancel_when_card_is_active_then_transitions_to_CANCELLED', () => {
    const card = createCard(CardStatus.ACTIVE)
    card.cancel()
    expect(card.status).toBe(CardStatus.CANCELLED)
  })

  it('cancel_when_card_is_suspended_then_transitions_to_CANCELLED', () => {
    const card = createCard(CardStatus.SUSPENDED)
    card.cancel()
    expect(card.status).toBe(CardStatus.CANCELLED)
  })

  it('cancel_when_card_is_already_cancelled_then_throws', () => {
    const card = createCard(CardStatus.CANCELLED)
    expect(() => card.cancel()).toThrow(CardErrorMessage['The card is already cancelled.'])
  })
})
