export class AccountSuspended {
  public readonly accountId: string
  public readonly suspendedAt: Date

  constructor(params: { accountId: string; suspendedAt: Date }) {
    this.accountId = params.accountId
    this.suspendedAt = params.suspendedAt
  }
}
