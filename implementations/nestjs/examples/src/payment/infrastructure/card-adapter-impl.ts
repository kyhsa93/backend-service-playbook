import { Injectable } from '@nestjs/common'

import { CardQuery } from '@/card/application/query/card-query'
import { CardStatus } from '@/card/card-enum'
import { CardErrorMessage } from '@/card/card-error-message'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'

// CardAdapter의 구현체(ACL). Card BC가 export한 읽기 서비스(CardQuery)를 주입받아
// 호출하고, Card BC의 모델·에러를 Payment BC가 쓰는 최소 형태로 번역한다.
// Card의 Repository·도메인 객체는 직접 참조하지 않는다.
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
      // 상류의 "카드 없음" 신호를 Payment 도메인이 이해하는 null로 번역한다 (오염 방지).
      if (error instanceof Error && error.message === CardErrorMessage['카드를 찾을 수 없습니다.']) return null
      throw error
    }
  }

  public async findActiveCards(): Promise<{ cardId: string; accountId: string; ownerId: string }[]> {
    return this.cardQuery.getActiveCards()
  }
}
