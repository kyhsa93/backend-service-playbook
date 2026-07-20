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

  set status(value: string) {
    this._status = value
  }
}
