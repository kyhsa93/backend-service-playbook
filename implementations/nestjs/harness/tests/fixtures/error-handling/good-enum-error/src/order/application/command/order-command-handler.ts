import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

export class OrderCommandHandler {
  public async execute(): Promise<void> {
    throw new Error(ErrorMessage['주문을 찾을 수 없습니다.'])
  }
}
