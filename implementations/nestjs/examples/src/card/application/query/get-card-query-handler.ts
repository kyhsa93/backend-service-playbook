import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { CardQuery } from '@/card/application/query/card-query'
import { GetCardResult } from '@/card/application/query/card-result'
import { GetCardQuery } from '@/card/application/query/get-card-query'

@QueryHandler(GetCardQuery)
export class GetCardQueryHandler implements IQueryHandler<GetCardQuery, GetCardResult> {
  constructor(private readonly cardQuery: CardQuery) {}

  public async execute(query: GetCardQuery): Promise<GetCardResult> {
    return this.cardQuery.getCard({ cardId: query.cardId, ownerId: query.requesterId })
  }
}
