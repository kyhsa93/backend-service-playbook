export class Order {
  public cancel(): void {
    // raw 문자열 — <Domain>ErrorMessage enum 참조가 아님 (타입화 안 된 에러)
    throw new Error('이미 취소된 주문입니다.')
  }
}
