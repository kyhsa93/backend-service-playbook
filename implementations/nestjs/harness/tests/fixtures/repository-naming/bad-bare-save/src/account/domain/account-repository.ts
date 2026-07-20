import { Account } from './account'

export abstract class AccountRepository {
  abstract findAccounts(query: { readonly take: number; readonly page: number }): Promise<{ accounts: Account[]; count: number }>

  abstract save(account: Account): Promise<void>
}
