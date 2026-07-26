import { GetAccountResult, GetTransactionsResult } from '@/account/application/query/account-result'
import { TransactionType } from '@/account/domain/transaction'

export abstract class AccountQuery {
  abstract getAccount(param: { accountId: string; ownerId: string }): Promise<GetAccountResult>

  abstract getTransactions(query: {
    accountId: string
    ownerId: string
    type?: TransactionType
    fromDate?: string
    toDate?: string
    take: number
    page: number
  }): Promise<GetTransactionsResult>
}
