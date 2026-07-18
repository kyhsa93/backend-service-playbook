import { Refund } from '@/payment/domain/refund'
import { RefundStatus } from '@/payment/payment-enum'

export abstract class RefundRepository {
  abstract findRefunds(query: {
    readonly take: number
    readonly page: number
    readonly refundId?: string
    readonly paymentId?: string
    readonly status?: RefundStatus[]
  }): Promise<{ refunds: Refund[]; count: number }>

  abstract saveRefund(refund: Refund): Promise<void>
}
