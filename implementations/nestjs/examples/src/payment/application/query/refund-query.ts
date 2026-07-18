import { GetRefundsResult } from '@/payment/application/query/refund-result'

export abstract class RefundQuery {
  abstract getRefunds(query: {
    paymentId: string
    ownerId: string
    take: number
    page: number
  }): Promise<GetRefundsResult>
}
