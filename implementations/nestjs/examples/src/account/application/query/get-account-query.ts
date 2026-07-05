export class GetAccountQuery {
  public readonly accountId: string
  public readonly requesterId: string

  constructor(query: GetAccountQuery) {
    Object.assign(this, query)
  }
}
