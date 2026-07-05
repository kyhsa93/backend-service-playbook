import { Account } from '@/account/domain/account'
import { AccountStatus } from '@/account/account-enum'

export abstract class AccountRepository {
  abstract findAccounts(query: {
    readonly take: number
    readonly page: number
    readonly accountId?: string
    readonly ownerId?: string
    readonly status?: AccountStatus[]
  }): Promise<{ accounts: Account[]; count: number }>

  abstract saveAccount(account: Account): Promise<void>
}
