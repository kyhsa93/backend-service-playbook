import { AccountRepository } from '../domain/account-repository'
import { Account } from '../domain/account'

export class AccountRepositoryImpl extends AccountRepository {
  async findAccounts(): Promise<{ accounts: Account[]; count: number }> {
    const count = await this.countByOwnerId('owner-1')
    return { accounts: [], count }
  }

  async saveAccount(): Promise<void> {}

  // An internal query-builder helper — an infrastructure implementation isn't a target of this rule.
  private async countByOwnerId(ownerId: string): Promise<number> {
    return 0
  }

  private async findByStatus(status: string): Promise<Account[]> {
    return []
  }
}
