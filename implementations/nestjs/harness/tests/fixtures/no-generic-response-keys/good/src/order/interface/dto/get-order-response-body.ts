// count가 없는 단건 응답의 'items'는 정당한 도메인 필드(주문 라인 아이템)다 —
// count와 함께 있을 때만 "목록 응답 래퍼"로 간주해야 하므로 오탐이 없어야 한다.
export class OrderItemResponseBody {
  readonly itemId: string
}

export class GetOrderResponseBody {
  readonly orderId: string

  readonly items: OrderItemResponseBody[]
}
