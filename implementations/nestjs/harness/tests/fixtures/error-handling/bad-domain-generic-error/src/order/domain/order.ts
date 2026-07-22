export class Order {
  public cancel(): void {
    // a raw string — not a <Domain>ErrorMessage enum reference (an untyped error)
    throw new Error('이미 취소된 주문입니다.')
  }
}
