import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { AccountQuery } from '@/account/application/query/account-query'
import { GetAccountResult } from '@/account/application/query/account-result'
import { GetAccountQuery } from '@/account/application/query/get-account-query'

@QueryHandler(GetAccountQuery)
export class GetAccountQueryHandler implements IQueryHandler<GetAccountQuery> {
  constructor(private readonly accountQuery: AccountQuery) {}

  public async execute(query: GetAccountQuery): Promise<GetAccountResult> {
    return this.accountQuery.getAccount({ accountId: query.accountId, ownerId: query.requesterId })
  }
}
