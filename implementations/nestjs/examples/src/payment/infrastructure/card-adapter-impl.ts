import { Injectable } from '@nestjs/common'

import { CardQuery } from '@/card/application/query/card-query'
import { CardStatus } from '@/card/card-enum'
import { CardErrorMessage } from '@/card/card-error-message'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'

// The CardAdapter implementation (ACL). Injects and calls the read service (CardQuery)
// exported by Card BC, translating Card BC's model·errors into the minimal shape Payment BC
// uses. Never references Card's Repository·domain objects directly.
@Injectable()
export class CardAdapterImpl extends CardAdapter {
  constructor(private readonly cardQuery: CardQuery) {
    super()
  }

  public async findCard(query: {
    readonly cardId: string
    readonly ownerId: string
  }): Promise<{ cardId: string; accountId: string; active: boolean } | null> {
    try {
      const card = await this.cardQuery.getCard({ cardId: query.cardId, ownerId: query.ownerId })
      return { cardId: card.cardId, accountId: card.accountId, active: card.status === CardStatus.ACTIVE }
    } catch (error) {
      // Translate the upstream "card not found" signal into a null the Payment domain understands (preventing model pollution).
      if (error instanceof Error && error.message === CardErrorMessage['카드를 찾을 수 없습니다.']) return null
      throw error
    }
  }

  public async findActiveCards(): Promise<{ cardId: string; accountId: string; ownerId: string }[]> {
    return this.cardQuery.getActiveCards()
  }
}
