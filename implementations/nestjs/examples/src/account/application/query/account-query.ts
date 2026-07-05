import { GetAccountResult, GetTransactionsResult } from '@/account/application/query/account-result'

export abstract class AccountQuery {
  abstract getAccount(param: { accountId: string; ownerId: string }): Promise<GetAccountResult>

  abstract getTransactions(query: {
    accountId: string
    ownerId: string
    take: number
    page: number
  }): Promise<GetTransactionsResult>
}
