import { Account } from './account'

export abstract class AccountRepository {
  abstract findByAccountIdAndOwnerId(accountId: string, ownerId: string): Promise<Account | undefined>

  abstract saveAccount(account: Account): Promise<void>
}
