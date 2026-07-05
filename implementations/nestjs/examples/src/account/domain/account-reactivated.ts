export class AccountReactivated {
  public readonly accountId: string
  public readonly reactivatedAt: Date

  constructor(params: { accountId: string; reactivatedAt: Date }) {
    this.accountId = params.accountId
    this.reactivatedAt = params.reactivatedAt
  }
}
