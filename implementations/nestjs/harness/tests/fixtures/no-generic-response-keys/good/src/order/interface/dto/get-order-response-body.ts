// 'items' in a single-record response with no count is a legitimate domain field (an order's
// line items) — it should only be treated as a "list response wrapper" when it's alongside a count, so there must be no false positive here.
export class OrderItemResponseBody {
  readonly itemId: string
}

export class GetOrderResponseBody {
  readonly orderId: string

  readonly items: OrderItemResponseBody[]
}
