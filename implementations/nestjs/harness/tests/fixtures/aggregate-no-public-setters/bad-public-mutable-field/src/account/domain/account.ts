export class Account {
  public readonly accountId: string
  public status: string

  constructor(params: { accountId: string; status: string }) {
    this.accountId = params.accountId
    this.status = params.status
  }
}
