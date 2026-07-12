export class CredentialFindQuery {
  public readonly page: number
  public readonly take: number
  public readonly userId?: string

  constructor(query: CredentialFindQuery) {
    Object.assign(this, query)
  }
}
