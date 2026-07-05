import { Injectable } from '@nestjs/common'

import { AccountQuery } from '@/account/application/query/account-query'
import { GetAccountResult, GetTransactionsResult } from '@/account/application/query/account-result'

@Injectable()
export class AccountQueryService {
  constructor(private readonly accountQuery: AccountQuery) {}

  public async getAccount(param: { accountId: string; requesterId: string }): Promise<GetAccountResult> {
    return this.accountQuery.getAccount({ accountId: param.accountId, ownerId: param.requesterId })
  }

  public async getTransactions(param: {
    accountId: string
    requesterId: string
    take: number
    page: number
  }): Promise<GetTransactionsResult> {
    return this.accountQuery.getTransactions({
      accountId: param.accountId,
      ownerId: param.requesterId,
      take: param.take,
      page: param.page
    })
  }
}
