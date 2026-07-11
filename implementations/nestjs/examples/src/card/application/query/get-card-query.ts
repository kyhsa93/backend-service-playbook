export class GetCardQuery {
  public readonly cardId: string
  public readonly requesterId: string

  constructor(query: GetCardQuery) {
    Object.assign(this, query)
  }
}
