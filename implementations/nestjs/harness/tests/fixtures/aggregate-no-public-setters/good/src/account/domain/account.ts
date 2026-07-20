export class Account {
  public readonly accountId: string
  private _status: string

  constructor(params: { accountId: string; status: string }) {
    this.accountId = params.accountId
    this._status = params.status
  }

  get status(): string {
    return this._status
  }

  public suspend(): void {
    this._status = 'SUSPENDED'
  }
}
