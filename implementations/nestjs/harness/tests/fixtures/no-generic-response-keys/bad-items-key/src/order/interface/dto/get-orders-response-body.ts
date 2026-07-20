export class GetOrderResponseBody {
  readonly orderId: string
}

export class GetOrdersResponseBody {
  readonly items: GetOrderResponseBody[]

  readonly count: number
}
