import { AccountRepository } from '../domain/account-repository'
import { Account } from '../domain/account'

export class AccountRepositoryImpl extends AccountRepository {
  async findAccounts(): Promise<{ accounts: Account[]; count: number }> {
    const count = await this.countByOwnerId('owner-1')
    return { accounts: [], count }
  }

  async saveAccount(): Promise<void> {}

  // 내부 query-builder 헬퍼 — infrastructure 구현체는 이 규칙의 대상이 아니다.
  private async countByOwnerId(ownerId: string): Promise<number> {
    return 0
  }

  private async findByStatus(status: string): Promise<Account[]> {
    return []
  }
}
