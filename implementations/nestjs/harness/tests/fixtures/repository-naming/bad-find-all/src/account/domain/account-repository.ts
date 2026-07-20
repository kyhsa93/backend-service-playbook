import { Account } from './account'

export abstract class AccountRepository {
  abstract findAll(query: { readonly take: number; readonly page: number }): Promise<{ accounts: Account[]; count: number }>

  abstract saveAccount(account: Account): Promise<void>
}
