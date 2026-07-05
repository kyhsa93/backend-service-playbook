export class AccountReactivated {
  public readonly accountId: string
  public readonly email: string
  public readonly reactivatedAt: Date

  constructor(params: { accountId: string; email: string; reactivatedAt: Date }) {
    this.accountId = params.accountId
    this.email = params.email
    this.reactivatedAt = params.reactivatedAt
  }
}
