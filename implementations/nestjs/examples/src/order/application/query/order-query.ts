import { GetOrderResult } from '@/order/application/query/get-order-result'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'

export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(param: { orderId: string }): Promise<GetOrderResult>
}
