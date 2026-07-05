export class AccountSuspended {
  public readonly accountId: string
  public readonly email: string
  public readonly suspendedAt: Date

  constructor(params: { accountId: string; email: string; suspendedAt: Date }) {
    this.accountId = params.accountId
    this.email = params.email
    this.suspendedAt = params.suspendedAt
  }
}
