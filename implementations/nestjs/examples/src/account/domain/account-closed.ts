export class AccountClosed {
  public readonly accountId: string
  public readonly closedAt: Date

  constructor(params: { accountId: string; closedAt: Date }) {
    this.accountId = params.accountId
    this.closedAt = params.closedAt
  }
}
