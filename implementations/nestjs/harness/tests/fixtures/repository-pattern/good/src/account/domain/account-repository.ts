export abstract class AccountRepository {
  abstract findAccounts(query: { accountId?: string; take: number; page: number }): Promise<{ accounts: unknown[]; count: number }>
  abstract saveAccount(account: unknown): Promise<void>
}
