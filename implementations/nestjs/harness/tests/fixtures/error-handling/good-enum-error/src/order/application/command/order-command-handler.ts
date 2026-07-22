import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

export class OrderCommandHandler {
  public async execute(): Promise<void> {
    throw new Error(ErrorMessage['Order not found.'])
  }
}
