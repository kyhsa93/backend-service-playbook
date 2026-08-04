export abstract class AccountQuery {
  abstract findAccountById(accountId: string): Promise<unknown>
}
