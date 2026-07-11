import { GetCardResult } from '@/card/application/query/card-result'

export abstract class CardQuery {
  abstract getCard(param: { cardId: string; ownerId: string }): Promise<GetCardResult>
}
