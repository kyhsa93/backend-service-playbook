export class AccountCreated {
  public readonly accountId: string
  public readonly ownerId: string
  public readonly email: string
  public readonly currency: string
  public readonly createdAt: Date

  constructor(params: { accountId: string; ownerId: string; email: string; currency: string; createdAt: Date }) {
    this.accountId = params.accountId
    this.ownerId = params.ownerId
    this.email = params.email
    this.currency = params.currency
    this.createdAt = params.createdAt
  }
}
