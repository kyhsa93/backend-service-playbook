export abstract class AccountRepository {
  abstract saveAccount(account: unknown): Promise<void>
}
