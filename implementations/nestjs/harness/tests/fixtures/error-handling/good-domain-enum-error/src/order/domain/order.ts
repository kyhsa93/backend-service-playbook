import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

export class Order {
  public cancel(): void {
    throw new Error(ErrorMessage['이미 취소된 주문입니다.'])
  }
}
