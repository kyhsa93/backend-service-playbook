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

  it('issue_when_정상_입력_then_ACTIVE_상태의_카드를_생성한다', () => {
    const card = Card.issue({ accountId: 'account-1', ownerId: 'owner-1', brand: 'VISA' })
    expect(card.status).toBe(CardStatus.ACTIVE)
    expect(card.accountId).toBe('account-1')
    expect(card.ownerId).toBe('owner-1')
    expect(card.cardId).toEqual(expect.any(String))
  })

  it('suspend_when_활성_카드_then_SUSPENDED로_전환한다', () => {
    const card = createCard(CardStatus.ACTIVE)
    card.suspend()
    expect(card.status).toBe(CardStatus.SUSPENDED)
  })

  it('suspend_when_이미_정지된_카드_then_에러를_throw한다', () => {
    const card = createCard(CardStatus.SUSPENDED)
    expect(() => card.suspend()).toThrow(CardErrorMessage['이미 정지된 카드입니다.'])
  })

  it('suspend_when_해지된_카드_then_에러를_throw한다', () => {
    const card = createCard(CardStatus.CANCELLED)
    expect(() => card.suspend()).toThrow(CardErrorMessage['해지된 카드는 정지할 수 없습니다.'])
  })

  it('cancel_when_활성_카드_then_CANCELLED로_전환한다', () => {
    const card = createCard(CardStatus.ACTIVE)
    card.cancel()
    expect(card.status).toBe(CardStatus.CANCELLED)
  })

  it('cancel_when_정지된_카드_then_CANCELLED로_전환한다', () => {
    const card = createCard(CardStatus.SUSPENDED)
    card.cancel()
    expect(card.status).toBe(CardStatus.CANCELLED)
  })

  it('cancel_when_이미_해지된_카드_then_에러를_throw한다', () => {
    const card = createCard(CardStatus.CANCELLED)
    expect(() => card.cancel()).toThrow(CardErrorMessage['이미 해지된 카드입니다.'])
  })
})
