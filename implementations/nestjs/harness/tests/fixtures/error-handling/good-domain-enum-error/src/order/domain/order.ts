import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

export class Order {
  public cancel(): void {
    throw new Error(ErrorMessage['The order is already cancelled.'])
  }
}
