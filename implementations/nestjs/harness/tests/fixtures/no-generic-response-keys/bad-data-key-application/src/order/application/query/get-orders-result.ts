export class GetOrderResult {
  readonly orderId: string
}

export class GetOrdersResult {
  readonly data: GetOrderResult[]

  readonly count: number
}
