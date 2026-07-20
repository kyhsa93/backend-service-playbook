import { Account } from './account'

export abstract class AccountRepository {
  abstract findAccounts(query: {
    readonly take: number
    readonly page: number
    readonly accountId?: string
    readonly ownerId?: string
  }): Promise<{ accounts: Account[]; count: number }>

  abstract saveAccount(account: Account): Promise<void>

  abstract deleteAccount(accountId: string): Promise<void>

  abstract hasTransactionWithReference(referenceId: string): Promise<boolean>
}
