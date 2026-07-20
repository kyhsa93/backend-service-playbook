export class GetOrderResponseBody {
  readonly orderId: string
}

export class GetOrdersResponseBody {
  readonly orders: GetOrderResponseBody[]

  readonly count: number
}
