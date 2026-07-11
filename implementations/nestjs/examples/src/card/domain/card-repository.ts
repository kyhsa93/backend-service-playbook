import { Card } from '@/card/domain/card'
import { CardStatus } from '@/card/card-enum'

export abstract class CardRepository {
  abstract findCards(query: {
    readonly take: number
    readonly page: number
    readonly cardId?: string
    readonly ownerId?: string
    readonly accountId?: string
    readonly status?: CardStatus[]
  }): Promise<{ cards: Card[]; count: number }>

  abstract saveCard(card: Card): Promise<void>
}
