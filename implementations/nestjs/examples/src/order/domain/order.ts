import { generateId } from '@/common/generate-id'
import { OrderCancelled } from '@/order/domain/order-cancelled'
import { OrderItem } from '@/order/domain/order-item'
import { OrderErrorMessage } from '@/order/order-error-message'

export type OrderDomainEvent = OrderCancelled

export class Order {
  public readonly orderId: string
  public readonly userId: string
  public readonly items: OrderItem[]
  private _status: 'pending' | 'paid' | 'cancelled'
  private readonly _events: OrderDomainEvent[] = []

  constructor(params: {
    orderId?: string
    userId: string
    items: OrderItem[]
    status: 'pending' | 'paid' | 'cancelled'
  }) {
    if (params.items.length === 0) throw new Error(OrderErrorMessage['주문 항목은 최소 1개 이상이어야 합니다.'])
    this.orderId = params.orderId ?? generateId()
    this.userId = params.userId
    this.items = params.items
    this._status = params.status
  }

  get status(): 'pending' | 'paid' | 'cancelled' { return this._status }
  get domainEvents(): OrderDomainEvent[] { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error(OrderErrorMessage['이미 취소된 주문입니다.'])
    if (this._status === 'paid') throw new Error(OrderErrorMessage['결제 완료된 주문은 취소할 수 없습니다.'])
    this._status = 'cancelled'
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public getTotalAmount(): number {
    return this.items.reduce((sum, item) => sum + item.price * item.quantity, 0)
  }

  public clearEvents(): void { this._events.length = 0 }
}
