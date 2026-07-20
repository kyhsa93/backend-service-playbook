import { Account } from './account'

export abstract class AccountRepository {
  abstract findAccounts(query: { readonly take: number; readonly page: number }): Promise<{ accounts: Account[]; count: number }>

  abstract saveAccount(account: Account): Promise<void>

  abstract delete(accountId: string): Promise<void>
}
