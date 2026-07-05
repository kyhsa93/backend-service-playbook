import { OrderErrorMessage } from '@/order/order-error-message'

export class OrderItem {
  public readonly itemId: number
  public readonly name: string
  public readonly price: number
  public readonly quantity: number

  constructor(params: { itemId: number; name: string; price: number; quantity: number }) {
    if (params.price <= 0) throw new Error(OrderErrorMessage['상품 가격은 0보다 커야 합니다.'])
    if (params.quantity <= 0) throw new Error(OrderErrorMessage['수량은 0보다 커야 합니다.'])
    this.itemId = params.itemId
    this.name = params.name
    this.price = params.price
    this.quantity = params.quantity
  }

  public equals(other: OrderItem): boolean {
    return this.itemId === other.itemId
      && this.name === other.name
      && this.price === other.price
      && this.quantity === other.quantity
  }
}
