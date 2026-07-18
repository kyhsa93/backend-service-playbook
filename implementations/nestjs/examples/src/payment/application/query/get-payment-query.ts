export class GetPaymentQuery {
  public readonly paymentId: string
  public readonly requesterId: string

  constructor(query: GetPaymentQuery) {
    Object.assign(this, query)
  }
}
