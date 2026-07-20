import { Account } from './account'

export abstract class AccountRepository {
  abstract findAccounts(query: { readonly take: number; readonly page: number }): Promise<{ accounts: Account[] }>

  abstract countByOwnerId(ownerId: string): Promise<number>

  abstract saveAccount(account: Account): Promise<void>
}
