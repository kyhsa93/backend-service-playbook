export class AccountClosed {
  public readonly accountId: string
  public readonly email: string
  public readonly closedAt: Date

  constructor(params: { accountId: string; email: string; closedAt: Date }) {
    this.accountId = params.accountId
    this.email = params.email
    this.closedAt = params.closedAt
  }
}
