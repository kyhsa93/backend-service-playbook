import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { AccountQuery } from '@/account/application/query/account-query'
import { GetTransactionsResult } from '@/account/application/query/account-result'
import { GetTransactionsQuery } from '@/account/application/query/get-transactions-query'

@QueryHandler(GetTransactionsQuery)
export class GetTransactionsQueryHandler implements IQueryHandler<GetTransactionsQuery> {
  constructor(private readonly accountQuery: AccountQuery) {}

  public async execute(query: GetTransactionsQuery): Promise<GetTransactionsResult> {
    return this.accountQuery.getTransactions({
      accountId: query.accountId,
      ownerId: query.requesterId,
      type: query.type,
      fromDate: query.fromDate,
      toDate: query.toDate,
      page: query.page,
      take: query.take
    })
  }
}
